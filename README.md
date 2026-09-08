# Speechmatics Two Way Audio

Expo module for capturing and playing pcm audio data in react-native apps (iOS and Android).

The aim of the module is to facilitate creating real-time conversational apps. The following features are provided:

- Request audio recording permissions
- Get clean (applying Acoustic Echo Cancelling) microphone samples in PCM format (1 channel 16 bit at 16kHz)
- Play audio samples in PCM format (1 channel 16 bit). Playback defaults to 24kHz and can be overridden during initialization. Playback happens through main speaker unless external audio sources are connected.
- Provide volume level both for the input and output samples. Float between 0 and 1.
- [iOS only] Get microphone mode and prompt user to select a microphone mode.

Check out our [examples/](./examples) to see the module in action.

## Installation

This fork is not on npm. Install a tagged release from GitHub:

```
npm i github:Paindrainer/expo-two-way-audio#v0.1.3-pd.2
```

See [Paindrainer fork](#paindrainer-fork) for what differs from upstream.

## Usage

Please check out our [examples/](./examples) to get full sample code.

1. Request permissions for recording audio

   ```JSX
   import {useMicrophonePermissions} from "@paindrainer/expo-two-way-audio";

   const [micPermission, requestMicPermission] = useMicrophonePermissions();
   console.log(micPermission);
   ```

1. Initialize the module before calling any audio functionality.

   ```JSX
   useEffect(() => {
       const initializeAudio = async () => {
           await initialize(); // Defaults playback to 24kHz
           // Or override it for 16kHz PCM sources: await initialize(16000);
       };
       initializeAudio();
   }, []);

   ```

1. Play audio

   > [!NOTE]
   > The sample below uses the `buffer` module:
   > `npm install buffer`

   ```JSX
    import { Buffer } from "buffer";

    // As an example, let's play pcm data hardcoded in a variable.
    // The examples/basic-usage does this. Check it out for real base64 data.
    // Make sure the PCM sample rate matches the value passed to initialize().
    const audioChunk = "SOME PCM DATA BASE64 ENCODED HERE"
    const buffer = Buffer.from(audioChunk, "base64");
    const pcmData = new Uint8Array(buffer);
    playPCMData(pcmData);
   ```

1. Get microphone samples

   ```JSX
   // Set up a function to deal with microphone sample events.
   // In this case just print the data in the console.
   useExpoTwoWayAudioEventListener(
       "onMicrophoneData",
       useCallback<MicrophoneDataCallback>((event) => {
           console.log(`MIC DATA: ${event.data}`);
       }, []),
   );

   // Unmute the microphone to get microphone data events
   toggleRecording(true);
   ```

## Paindrainer fork

This is Paindrainer's fork of [speechmatics/expo-two-way-audio](https://github.com/speechmatics/expo-two-way-audio).
It is not published to npm — consumers install a tagged release straight from
this repository:

```
npm i github:Paindrainer/expo-two-way-audio#v0.1.3-pd.2
```

Fork versions carry a `-pd.N` suffix on top of the upstream version they are
based on, and every release gets a matching `vX.Y.Z-pd.N` tag. Pin the tag, not
a commit SHA, and do not patch this package with `patch-package` in a consuming
app — fix it here and cut a new tag instead, so every app gets the same code.

The package is named `@paindrainer/...` rather than keeping upstream's
`@speechmatics/...`. Under the old name, an `npm i @speechmatics/expo-two-way-audio`
that lost the `github:` prefix would silently install upstream 0.1.2 from the real
npm scope and drop the Android fixes below with no error. No `@paindrainer` package
exists on npm, so the same mistake now fails loudly.

### Changes on top of upstream 0.1.2

- Android: the mic sample tap no longer tears the whole engine down when it
  loses the microphone (backgrounding, another app taking it, `AudioRecord`
  released under a blocking read). It used to throw from a worker thread and the
  next `startRecording()` hit shut-down executors, crashing the app with
  `RejectedExecutionException`.
- Android: `AudioEngine` tracks `isTornDown`, so calls arriving after teardown
  are no-ops rather than crashes, and `stopRecording()` tolerates `stop()` /
  `release()` throwing.
- Android: `initialize()` rebuilds a torn-down engine instead of handing back a
  dead one.
- Android: build with `expo-module-gradle-plugin` for Expo SDK 57.
- Android: route-specific audio profiles, configurable playback sample rate
  (24 kHz default).
- iOS: Expo SDK 57 permission API compatibility.

### Cutting a release

1. Commit the change on `main`.
2. Bump `version` in `package.json` to the next `-pd.N`.
3. Tag it `vX.Y.Z-pd.N` and push the tag.
4. Point the consuming app's `package.json` at the new tag and reinstall.

## Notes

Some audio features of expo-two-way-audio like Acoustic Echo Cancelling, noise reduction or microphone modes (iOS) don't work on simulator. Run the example on a real device to test these features.

```bash
# iOS
npx expo run:ios --device --configuration Release

# Android
npx expo run:android --device --variant release
```

For Android, the following permissions are needed: `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`. In Expo apps they can bee added in your `app.json` file:

```javascript
expo.android.permissions: ["RECORD_AUDIO", "MODIFY_AUDIO_SETTINGS"]
```
