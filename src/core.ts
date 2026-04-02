import { type PermissionResponse } from "expo-modules-core";

import ExpoTwoWayAudioModule from "./ExpoTwoWayAudioModule";

export const DEFAULT_PLAYBACK_SAMPLE_RATE = 24000;

export async function initialize(
  playbackSampleRate: number = DEFAULT_PLAYBACK_SAMPLE_RATE,
) {
  const resolvedPlaybackSampleRate =
    Number.isFinite(playbackSampleRate) && playbackSampleRate > 0
      ? Math.round(playbackSampleRate)
      : DEFAULT_PLAYBACK_SAMPLE_RATE;

  return await ExpoTwoWayAudioModule.initialize(resolvedPlaybackSampleRate);
}

export function playPCMData(audioData: Uint8Array) {
  return ExpoTwoWayAudioModule.playPCMData(audioData);
}

export function bypassVoiceProcessing(bypass: boolean) {
  return ExpoTwoWayAudioModule.bypassVoiceProcessing(bypass);
}

export function toggleRecording(val: boolean): boolean {
  return ExpoTwoWayAudioModule.toggleRecording(val);
}

export function isRecording(): boolean {
  return ExpoTwoWayAudioModule.isRecording();
}

export function tearDown() {
  return ExpoTwoWayAudioModule.tearDown();
}

export function restart() {
  return ExpoTwoWayAudioModule.restart();
}

export async function getMicrophonePermissionsAsync(): Promise<PermissionResponse> {
  return ExpoTwoWayAudioModule.getMicrophonePermissionsAsync();
}

export async function requestMicrophonePermissionsAsync(): Promise<PermissionResponse> {
  return ExpoTwoWayAudioModule.requestMicrophonePermissionsAsync();
}

export function getMicrophoneModeIOS() {
  return ExpoTwoWayAudioModule.getMicrophoneModeIOS();
}

export function setMicrophoneModeIOS() {
  return ExpoTwoWayAudioModule.setMicrophoneModeIOS();
}
