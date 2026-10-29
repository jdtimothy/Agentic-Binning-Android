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

Fresh Jetpack Compose app (package `com.agentic.binning`); the GenieX chat
sample has been removed. Work is split into three plans under
`docs/superpowers/plans/`. Reference copies of the useful sample code live in
`docs/reference/sample/` (`GgufVisionConfig.kt.txt`, `MediaUtil.kt.txt`,
`MainActivity.kt.txt`); they carry a `.txt` extension so they are not compiled.

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
`GGML_ASSERT`.

- `connectedDebugAndroidTest` uninstalls the app afterwards, which deletes the
  pulled models (`files/geniex/models`). Add
  `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep them,
  or rerun a single test with `adb shell am instrument -w -e class <Class>#<method>
  com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner`.
- The vision layer logs the prompt as sent and every reply's profile under the
  `VisionModel` tag (`adb logcat -s VisionModel:D`).
