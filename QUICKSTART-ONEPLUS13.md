# Quick start: OnePlus 13

1. Build the device-specific APK from `VoicePingAndroidOfflineTranscribe`:

   ```powershell
   .\gradlew.bat packageOneplus13Debug
   ```

2. Install the generated `build/outputs/apk/oneplus13/OfflineTranscribeLab-oneplus13-debug.apk`:

   ```powershell
   adb install -r build\outputs\apk\oneplus13\OfflineTranscribeLab-oneplus13-debug.apk
   ```

3. Launch **Offline Transcribe Lab**, choose and download an offline model. For Russian, use
   **Qwen3 ASR 0.6B (ONNX)** when storage is available; download is explicit and remains local.

4. Tap the microphone control and grant microphone access. A visible Android foreground-service
   notification is shown while a microphone session is active; the app does not silently record.

5. To caption permitted playback audio, choose **System** input and approve the Android
   MediaProjection prompt for that session. Android does not permit silently renewing it later.

6. Autonomous Capture is off by default. If you decide to use it, enable the app's Accessibility
   service in Android Settings and configure an explicit app allowlist. It excludes password
   fields and sensitive package categories; do not enable it for apps you do not want archived.

## Device baseline captured for this fork

- OnePlus PJZ110 / Snapdragon 8 Elite (SM8750)
- Android 16 (API 36), arm64-v8a
- approximately 23.6 GB RAM visible to Android

The OnePlus build includes arm64-v8a native libraries only. It is intentionally not an emulator or
x86_64 artifact.
