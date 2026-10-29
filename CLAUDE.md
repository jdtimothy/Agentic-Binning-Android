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

## Test media and the end-to-end run

`scripts/make-test-media.sh` builds the Plan 1 set in `build/test-media/`
(square turning red at 5 s, 60 s bars, 0.3 s clip, 0-byte `empty.mp4`, "HELLO
S26" still, red/blue still, 2 KB `still_truncated.jpg`) and one check frame per
clip in `build/test-media-check/`; look at those before trusting the clips.

```bash
bash scripts/make-test-media.sh
MSYS_NO_PATHCONV=1 adb push build/test-media /sdcard/Movies/avb-plan1
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/avb-plan1
```

Expected: 7 added, then "7 already in this project" on a second add;
`empty.mp4` and `still_truncated.jpg` end Failed with reason `decode`, the rest
Analyzed with `stopReason` `eos`.

- Projects live in `/storage/emulated/0/Agentic Binning/<name>/.binning/project.db`
  (journal mode `delete`, so a plain `adb pull` of the `.db` is consistent).
  Room columns are camelCase:
  `sqlite3 build/project.db "select originalName,status,failureReason from item; select itemId,stopReason,generatedTokens from analysis;"`.
- Caps to compare `generatedTokens` against: 400 for photos, 520 for videos
  (`AnalysisPrompt.MAX_TOKENS_*`).
- Drive the add-media flow with `uiautomator dump` + `input tap`: project button
  opens the sheet, "Add a folder" opens the system picker (breadcrumbs work),
  then "USE THIS FOLDER" and "ALLOW". Don't swipe on the sheet while the
  keyboard is up: the swipe types into the name field.
- `uiautomator dump` can fail with "could not get idle state" while the Analyze
  screen animates; take a `screencap` instead.
- `ffmpeg -qp 0` (lossless x264) writes High 4:4:4 Predictive, which the
  phone's decoders can't read: such clips fail with `decode`, not a bug.
- The device clock runs about 1.3 s ahead of the PC; compare logcat times with
  `adb shell date`, not the host clock.
- On Git Bash, `adb logcat | grep -m1 ...` never returns (adb.exe gets no
  SIGPIPE). Capture logcat to a file in the background and poll the file.
