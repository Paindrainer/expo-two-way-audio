import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.LinkedList
import java.util.Queue
import java.util.concurrent.Executors
import kotlin.math.pow


private const val MICROPHONE_SAMPLE_RATE = 16000
private const val DEFAULT_PLAYBACK_SAMPLE_RATE = 24000

class AudioEngine (context: Context, initialPlaybackSampleRate: Int = DEFAULT_PLAYBACK_SAMPLE_RATE) {
    private val playbackSampleRate = initialPlaybackSampleRate
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

    private lateinit var audioRecord: AudioRecord
    private lateinit var audioManager: AudioManager
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioSampleQueue: Queue<ByteArray> = LinkedList()
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val executorServiceMicrophone = Executors.newFixedThreadPool(1)
    private val executorServicePlayback = Executors.newFixedThreadPool(1)
    private var speakerDevice: AudioDeviceInfo? = null
    private val audioTrackLock = Any()
    // Profile currently in use by audioTrack: true => voice/call, false => media.
    // null means no track yet built.
    private var currentTrackUsesVoiceProfile: Boolean? = null

    var isRecording = false
    private var isRecordingBeforePause = false
    var isPlaying = false

    val currentPlaybackSampleRate: Int
        get() = playbackSampleRate

    val currentTrackUsesVoiceProfilePublic: Boolean?
        get() = currentTrackUsesVoiceProfile

    // Callbacks
    var onMicDataCallback: ((ByteArray) -> Unit)? = null
    var onInputVolumeCallback: ((Float) -> Unit)? = null
    var onOutputVolumeCallback: ((Float) -> Unit)? = null
    var onAudioInterruptionCallback: ((String) -> Unit)? = null
    // Fires whenever the active route changes profile so callers can wire
    // up Activity.setVolumeControlStream() — hardware volume keys are
    // governed by that, not by AudioAttributes.
    var onAudioProfileChanged: ((useVoiceProfile: Boolean) -> Unit)? = null

    init {
        initializeAudio(context)
    }

    @SuppressLint("NewApi")
    private fun initializeAudio(context:Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // MODE_IN_COMMUNICATION + setCommunicationDevice is required so the
        // mic capture (VOICE_COMMUNICATION source) and the AudioTrack output
        // both honor the chosen route. The AudioTrack is rebuilt with
        // device-appropriate AudioAttributes whenever the route changes.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Pick the route first so the focus request and AudioTrack get the
        // right AudioAttributes profile.
        updateAudioRouting()

        // Listen for changes in audio routing
        audioManager.registerAudioDeviceCallback(object:android.media.AudioDeviceCallback(){
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d("AudioEngine", "onAudioDevicesAdded")
                super.onAudioDevicesAdded(addedDevices)
                updateAudioRouting()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d("AudioEngine", "onAudioDevicesRemoved")
                super.onAudioDevicesRemoved(removedDevices)
                updateAudioRouting()
            }
        }, null)
    }

    /**
     * Voice profile (USAGE_VOICE_COMMUNICATION + CONTENT_TYPE_SPEECH) routes
     * playback to STREAM_VOICE_CALL — used when the active output is the
     * built-in earpiece/speaker so hardware volume keys map to call volume
     * and the speaker drives at its loud "in-call" level.
     *
     * Media profile (USAGE_MEDIA + CONTENT_TYPE_MUSIC) routes playback to
     * STREAM_MUSIC — used for wired or BT headsets so A2DP/BLE works
     * naturally and hardware volume keys map to media volume on the device.
     * BT mic capture still works because mic source uses VOICE_COMMUNICATION
     * which follows setCommunicationDevice() (typically the SCO/BLE device).
     */
    private fun shouldUseVoiceProfile(deviceType: Int?): Boolean {
        return when (deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> true
            else -> false
        }
    }

    private fun buildAudioAttributes(useVoiceProfile: Boolean): AudioAttributes {
        return if (useVoiceProfile) {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        } else {
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        }
    }

    @SuppressLint("NewApi")
    private fun ensureAudioTrackForProfile(useVoiceProfile: Boolean) {
        var profileChanged = false
        synchronized(audioTrackLock) {
            if (currentTrackUsesVoiceProfile == useVoiceProfile && audioTrack != null) {
                return
            }
            profileChanged = true

            // Tear down the existing track so we can rebuild with attributes
            // that match the active route.
            audioTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        track.pause()
                    }
                    track.flush()
                    track.release()
                } catch (e: Exception) {
                    Log.e("AudioEngine", "Error releasing previous AudioTrack", e)
                }
            }
            audioTrack = null

            // Refresh focus with attributes matching the new profile so the
            // OS treats us as the correct kind of audio source.
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            requestAudioFocus(useVoiceProfile)

            val bufferSize = AudioTrack.getMinBufferSize(
                playbackSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            )

            val newTrack = AudioTrack(
                buildAudioAttributes(useVoiceProfile),
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(playbackSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                audioManager.generateAudioSessionId()
            )
            newTrack.play()
            audioTrack = newTrack
            currentTrackUsesVoiceProfile = useVoiceProfile
            Log.d(
                "AudioEngine",
                "AudioTrack built with " + if (useVoiceProfile) "voice profile" else "media profile"
            )
        }
        if (profileChanged) {
            try {
                onAudioProfileChanged?.invoke(useVoiceProfile)
            } catch (e: Exception) {
                Log.e("AudioEngine", "onAudioProfileChanged threw", e)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun updateAudioRouting() {
        // Prefer SCO / BLE headset over A2DP for BT, because A2DP has no mic
        // path. Wired headsets next, then fall back to the built-in speaker.
        val priority = intArrayOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )

        val candidates: List<AudioDeviceInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }

        speakerDevice = candidates.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: speakerDevice

        var selectedDevice: AudioDeviceInfo? = null
        for (type in priority) {
            selectedDevice = candidates.firstOrNull { it.type == type }
            if (selectedDevice != null) break
        }
        if (selectedDevice == null) selectedDevice = speakerDevice

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                selectedDevice?.let { audioManager.setCommunicationDevice(it) }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Error setting communication device. Using speaker", e)
                speakerDevice?.let { audioManager.setCommunicationDevice(it) }
                selectedDevice = speakerDevice
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn =
                selectedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }

        val useVoiceProfile = shouldUseVoiceProfile(selectedDevice?.type)
        ensureAudioTrackForProfile(useVoiceProfile)
        Log.d(
            "AudioEngine",
            "Routing => device=${selectedDevice?.type}, voiceProfile=$useVoiceProfile"
        )
    }

    @SuppressLint("NewApi")
    private fun requestAudioFocus(useVoiceProfile: Boolean = currentTrackUsesVoiceProfile ?: true) {
        val focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(buildAudioAttributes(useVoiceProfile))
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.d("AudioEngine", "Audio focus lost")
                            onAudioInterruptionCallback?.let { it("blocked") }
                        }
                    }
                }
                .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw RuntimeException("Audio focus request failed")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun startRecording(){
        val bufferSize = AudioRecord.getMinBufferSize(MICROPHONE_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MICROPHONE_SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("Audio Record can't initialize!")
        }

        if (AcousticEchoCanceler.isAvailable()){
            echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)
            if (echoCanceler != null) {
                echoCanceler?.enabled = true
                Log.i("AudioEngine", "Echo Canceler enabled")
            }
        }

        if (NoiseSuppressor.isAvailable()){
            noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
            if (noiseSuppressor != null) {
                noiseSuppressor?.enabled = true
                Log.i("AudioEngine", "Noise Suppressor enabled")
            }
        }

        audioRecord.startRecording()
        isRecording = true
        startMicSampleTap()
    }

    private fun startMicSampleTap(){
        executorServiceMicrophone.execute {
            val buffer = ByteArray(1024)
            try {
                while (isRecording) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        val micVolume = calculateRMSLevel(data)
                        onInputVolumeCallback?.invoke(micVolume)
                        onMicDataCallback?.invoke(data)
                    }
                }
                Log.d("AudioEngine", "Mic sample tap stopped.")
            }catch (e: Exception){
                Log.e("AudioEngine", "Error reading mic sample data", e)
                isRecording = false
                tearDown()
                throw e
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
            audioRecord.release()
        }
        onInputVolumeCallback?.invoke(0.0F)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun toggleRecording(value: Boolean): Boolean {
        if (value == isRecording) return isRecording

        if (value) {
            startRecording()
        } else {
            stopRecording()
        }

        isRecording = value
        return isRecording
    }

    fun playPCMData(data: ByteArray) {
        audioSampleQueue.add(data)
        if (!isPlaying) {
            playAudioFromSampleQueue()
        }
    }

    private fun playAudioFromSampleQueue() {
        executorServicePlayback.execute{
            isPlaying = true
            try {
                while (audioSampleQueue.isNotEmpty()){
                    val data = audioSampleQueue.poll()
                    if (data != null){
                        playSample(data)
                        val audioVolume = calculateRMSLevel(data)
                        onOutputVolumeCallback?.invoke(audioVolume)
                    }else{
                        break
                    }
                }
            }catch (e: Exception){
                Log.e("AudioEngine", "Error playing audio", e)
                e.printStackTrace()
            }finally {
                isPlaying = false
                onOutputVolumeCallback?.invoke(0.0F)
            }
        }
    }

    private fun playSample(data: ByteArray) {
        // Hold the lock so a route-change rebuild can't release the track
        // out from under a write in progress.
        synchronized(audioTrackLock) {
            audioTrack?.write(data, 0, data.size)
        }
    }

    fun bypassVoiceProcessing(bypass: Boolean) {
        if (bypass) {
            echoCanceler?.enabled = false
            noiseSuppressor?.enabled = false
        } else {
            echoCanceler?.enabled = true
            noiseSuppressor?.enabled = true
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun pauseRecordingAndPlayer() {
        isRecordingBeforePause = isRecording
        isRecording = toggleRecording(false)
        synchronized(audioTrackLock) {
            audioTrack?.pause()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun resumeRecordingAndPlayer() {
        requestAudioFocus()
        isRecording = toggleRecording(isRecordingBeforePause)
        synchronized(audioTrackLock) {
            audioTrack?.play()
        }
    }

    @SuppressLint("NewApi")
    fun tearDown() {
        stopRecording()
        executorServicePlayback.shutdownNow()
        audioSampleQueue.clear()
        synchronized(audioTrackLock) {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                track.release()
            }
            audioTrack = null
            currentTrackUsesVoiceProfile = null
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
        }
        executorServiceMicrophone.shutdownNow()
    }


    private fun calculateRMSLevel(buffer: ByteArray): Float {
        val epsilon = 1e-5f // To avoid log(0)

        // Convert ByteArray to FloatArray by treating each pair of bytes as a single 16-bit PCM sample
        val floatBuffer = FloatArray(buffer.size / 2)
        for (i in floatBuffer.indices) {
            // Combine two bytes into a 16-bit signed integer
            val sample = (buffer[i * 2].toInt() or (buffer[i * 2 + 1].toInt() shl 8)).toShort()
            // Normalize sample to -1.0 to 1.0 range for FloatArray
            floatBuffer[i] = sample / 32768.0f
        }

        // Calculate RMS value
        val rmsValue = kotlin.math.sqrt(floatBuffer.fold(0f) { acc, sample -> acc + sample * sample } / floatBuffer.size)

        // Convert to decibels
        val dbValue = 20 * kotlin.math.log10(maxOf(rmsValue, epsilon))

        // Normalize decibel value to 0-1 range
        // Assuming minimum audible is -80dB and maximum is 0dB
        val minDb = -80.0f
        val normalizedValue = maxOf(0.0f, minOf(1.0f, (dbValue - minDb) / kotlin.math.abs(minDb)))

        // Optional: Apply exponential factor to push smaller values down
        val expFactor = 2.0f // Adjust this value to change the curve
        val adjustedValue = normalizedValue.pow(expFactor)

        return adjustedValue
    }

}
