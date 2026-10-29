# Agentic Binning (Android)

Android port of Agentic Video Binning (`..\Agentic-Video-Binning`,
the desktop pipeline): analyze every photo and video shot on-device with
LFM3-VL-2B on the Galaxy S26 Ultra's Hexagon NPU via GenieX, store structured
results in SQLite, and search the library without waiting on the model.

**Read `Instructions.md` before touching GenieX code.** It holds every fix found
on the phone (batch sizes, sampling, `maxTokens`, video frame format, the media
interleaving workaround) plus the target architecture and schema. Its gotchas
checklist is the review list for any model-facing change.

## Current state

The code is still the patched `geniex_chat_android` sample (copied from
`..\geniex-android`), renamed to package `com.agentic.binning`.
It runs as a chat app and serves as a known-good NPU baseline. The binning
pipeline (MediaStore scan, shot split, per-shot analysis, SQLite + FTS5,
search UI) is not built yet; replace the chat pieces as it lands.

Worth keeping from the sample: `utils/GgufVisionConfig.kt` (vision geometry from
the mmproj), `utils/ImgUtil.kt` / `utils/MediaUtil.kt` (decode, pad, frame
extraction), and the model download/load flow in `MainActivity.kt`.

## Build and install

No Android Studio needed. From Git Bash in this folder:

```bash
JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot" ./gradlew assembleDebug
adb install -r -t build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.agentic.binning/.MainActivity
```

- `local.properties` (gitignored) must contain
  `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`.
- Pinned versions: AGP 8.13.0, Kotlin 2.2.0, Gradle 9.1.0, compileSdk 35,
  targetSdk 34, minSdk 31, `com.qualcomm.qti:geniex-android:0.10.0`. Don't bump
  targetSdk to 35 (forces edge-to-edge) or drop `jniLibs.useLegacyPackaging`.
- Set `MSYS_NO_PATHCONV=1` when passing `/sdcard/...` paths to `adb` from Git Bash.

## On-device testing

Read results from logcat, not the screen. Turn on Do Not Disturb before
scripting taps. Native crashes: `adb logcat -d -b crash`, then grep for
`GGML_ASSERT`. The instrumented test in `src/androidTest` drives the inherited
chat flow with UI Automator (`./gradlew connectedDebugAndroidTest`).
