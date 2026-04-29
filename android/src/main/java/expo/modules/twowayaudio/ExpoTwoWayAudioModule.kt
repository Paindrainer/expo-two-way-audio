package expo.modules.twowayaudio

import AudioEngine
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.os.bundleOf
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class ExpoTwoWayAudioModule : Module() {
    companion object {
        private const val ON_MIC_DATA_EVENT = "onMicrophoneData"
        private const val ON_INPUT_VOLUME_LEVEL_EVENT = "onInputVolumeLevelData"
        private const val ON_OUTPUT_VOLUME_LEVEL_EVENT = "onOutputVolumeLevelData"
        private const val ON_RECORDING_CHANGE_EVENT = "onRecordingChange"
        private const val ON_AUDIO_INTERRUPTION_EVENT = "onAudioInterruption"
        private const val DEFAULT_PLAYBACK_SAMPLE_RATE = 24000
        private const val LOG_TAG = "ExpoTwoWayAudio"
        var audioEngine: AudioEngine? = null
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoTwoWayAudio")

        AsyncFunction("initialize") { playbackSampleRate: Int, promise: Promise ->
            val resolvedPlaybackSampleRate = if (playbackSampleRate > 0) {
                playbackSampleRate
            } else {
                DEFAULT_PLAYBACK_SAMPLE_RATE
            }

            try {
                val existingEngine = audioEngine
                if (existingEngine != null) {
                    if (existingEngine.currentPlaybackSampleRate == resolvedPlaybackSampleRate) {
                        promise.resolve(true)
                        return@AsyncFunction
                    }
                    existingEngine.tearDown()
                    audioEngine = null
                }

                audioEngine = appContext.reactContext?.let { AudioEngine(it, resolvedPlaybackSampleRate) }
                setupCallbacks()
                promise.resolve(audioEngine != null)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Initialize failed", e)
                promise.resolve(false)
            }
        }

        Function("isRecording") {
            audioEngine?.isRecording ?: false
        }

        Function("toggleRecording") { value: Boolean ->
            audioEngine?.let { engine ->
                val isRecording = engine.toggleRecording(value)
                sendEvent(ON_RECORDING_CHANGE_EVENT, mapOf("data" to isRecording))
                isRecording
            } ?: false
        }

        Function("tearDown") {
            audioEngine?.tearDown()
            audioEngine = null
            null
        }

        Function("restart") {
            audioEngine?.resumeRecordingAndPlayer()
            sendEvent(
                ON_RECORDING_CHANGE_EVENT,
                mapOf("data" to (audioEngine?.isRecording ?: false)),
            )
        }

        Function("playPCMData") { data: kotlin.ByteArray ->
            audioEngine?.playPCMData(data)
        }

        Function("bypassVoiceProcessing") { bypass: Boolean ->
            audioEngine?.bypassVoiceProcessing(bypass)
        }

        Function("isPlaying") {
            audioEngine?.isPlaying ?: false
        }

        Function("getMicrophoneModeIOS") {
            throw UnsupportedOperationException("getMicrophoneModeIOS is only supported on iOS")
        }

        Function("setMicrophoneModeIOS") {
            throw UnsupportedOperationException("setMicrophoneModeIOS is only supported on iOS")
        }

        AsyncFunction("getMicrophonePermissionsAsync") { promise: Promise ->
            Permissions.getPermissionsWithPermissionsManager(
                appContext.permissions,
                promise,
                android.Manifest.permission.RECORD_AUDIO,
            )
        }

        AsyncFunction("requestMicrophonePermissionsAsync") { promise: Promise ->
            Permissions.askForPermissionsWithPermissionsManager(
                appContext.permissions,
                promise,
                android.Manifest.permission.RECORD_AUDIO,
            )
        }

        Events(
            ON_MIC_DATA_EVENT,
            ON_INPUT_VOLUME_LEVEL_EVENT,
            ON_OUTPUT_VOLUME_LEVEL_EVENT,
            ON_RECORDING_CHANGE_EVENT,
            ON_AUDIO_INTERRUPTION_EVENT,
        )
    }

    private fun setupCallbacks() {
        audioEngine?.apply {
            onMicDataCallback = { data ->
                sendEvent(ON_MIC_DATA_EVENT, bundleOf("data" to data))
            }
            onInputVolumeCallback = { level ->
                sendEvent(ON_INPUT_VOLUME_LEVEL_EVENT, bundleOf("data" to level))
            }
            onOutputVolumeCallback = { level ->
                sendEvent(ON_OUTPUT_VOLUME_LEVEL_EVENT, bundleOf("data" to level))
            }
            onAudioInterruptionCallback = { data ->
                sendEvent(ON_AUDIO_INTERRUPTION_EVENT, bundleOf("data" to data))
                sendEvent(
                    ON_RECORDING_CHANGE_EVENT,
                    bundleOf("data" to (audioEngine?.isRecording ?: false)),
                )
            }
            onAudioProfileChanged = { _, useCallVolumeStream ->
                // setVolumeControlStream is what binds the hardware volume
                // keys to a stream; AudioAttributes alone do not. Must be
                // called on the activity on the UI thread.
                // STREAM_MUSIC for headsets so the on-device volume slider
                // shows "Bluetooth"/media even when AudioTrack uses voice
                // attributes for SCO/BLE routing.
                val stream = if (useCallVolumeStream) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
                Handler(Looper.getMainLooper()).post {
                    appContext.currentActivity?.volumeControlStream = stream
                }
            }
        }
        // Apply the current preference right away in case the route was
        // already resolved before this callback was attached.
        audioEngine?.let { engine ->
            val stream = if (engine.currentUsesCallVolumeStreamPublic == true) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
            Handler(Looper.getMainLooper()).post {
                appContext.currentActivity?.volumeControlStream = stream
            }
        }
    }
}
