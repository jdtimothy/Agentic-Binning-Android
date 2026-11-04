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

Jetpack Compose app (package `com.agentic.binning`). Plans live under
`docs/superpowers/plans/`:

- **Plan 1** (foundation and analysis) and **Plan 2a** (sorting with LFM3-1.7B
  or Claude, `ModelArbiter`, schema v3) are merged into `main`. 2a's
  authenticated Claude request passed on the phone on Nov 3 (Maui, 35 items;
  `docs/superpowers/notes/2026-11-01-plan-2a-phone-recheck.md`). Keep LFM3-1.7B
  selected and never make a paid Claude call without the owner. A network that blocks api.anthropic.com makes Claude report "No connection".
- **Plan 2b** (Project Media, editing, Re-sort, the failed item sheet, and
  Task 6A: Open project and Edit/× on Current projects) is done and merged
  into `main`, phone checks included:
  `docs/superpowers/plans/2026-10-31-plan-2b-project-media.md`. Its "Product
  decisions" section holds every owner decision; "Left for Plan 3" lists what
  binning inherits. Mockups are in `docs/superpowers/mockups/` (open the HTML
  files in a browser; GitHub doesn't render them).
- **Plan 3** (binning: moving files) is being planned on `plan-3-binning` by a
  cloud session, from `docs/superpowers/plan-3-brief.md`.
- After release the owner will try other sorting models; LFM3-1.7B's odd
  calls (file-type categories, self-talk descriptions) are expected of a
  small model.

Reference copies of the useful GenieX sample code live in
`docs/reference/sample/` (`GgufVisionConfig.kt.txt`, `MediaUtil.kt.txt`,
`MainActivity.kt.txt`); they carry a `.txt` extension so they are not compiled.

Cloud sessions (no phone attached) have OpenJDK 21 and the SDK at
`/root/android-sdk`; use that Java, not the Windows `JAVA_HOME` below. Maven
Central rate-limits them (HTTP 429): retry with backoff, never change
dependency versions or repositories to get past it.

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
  (journal mode TRUNCATE, set in `ProjectDatabase.kt`, not WAL, so there is no
  `-wal` file and a plain `adb pull` of the `.db` between writes is complete; a
  pulled copy reports `delete` because only WAL is persisted in the file).
  Room columns are camelCase:
  `sqlite3 build/project.db "select originalName,status,failureReason from item; select itemId,stopReason,generatedTokens from analysis;"`.
- Caps to compare `generatedTokens` against: 400 for photos, 520 for videos
  (`AnalysisPrompt.MAX_TOKENS_*`).
- Drive the add-media flow with `uiautomator dump` + `input tap`: the project
  chip opens the sheet: "Current project" with "Add a folder" / "Pick photos
  and clips", then "Current projects" with "Open project" / "New project",
  "Edit" (× on each row, "Done" to leave), and the list (the open project
  first; without a project only the Current projects section shows). "Add a
  folder" and "Open project" open the system folder picker (breadcrumbs work),
  then "USE THIS FOLDER" and "ALLOW". "New project" opens the New project
  page: name, optional "A folder" / "Photos and clips" tile (held until
  "Create and analyze"), back arrow or system Back to return. × only takes a
  project off the list; its files and database stay. Don't swipe on the sheet
  while the keyboard is up: the swipe types into the name field.
- Registration matches files across projects by content hash: media already in
  another project is reported "in another project (<name>)", not added. The
  Plan 1 set in `Movies/avb-plan1` belongs to "Plan1 E2E", so use fresh media
  to see items added to a new project.
- `uiautomator dump` can fail with "could not get idle state" while the Analyze
  screen animates; take a `screencap` instead.
- `ffmpeg -qp 0` (lossless x264) writes High 4:4:4 Predictive, which the
  phone's decoders can't read: such clips fail with `decode`, not a bug.
- The device clock runs about 1.3 s ahead of the PC; compare logcat times with
  `adb shell date`, not the host clock.
- On Git Bash, `adb logcat | grep -m1 ...` never returns (adb.exe gets no
  SIGPIPE). Capture logcat to a file in the background and poll the file.
- Scripting Project Media: wait about a second after tapping a text field
  before `input text`, or the first letters or the whole text are lost. Don't
  send Back after a field's Done or Add: the keyboard is already down, so Back
  closes the sheet and drops its edits. Match buttons exactly: "Save" also
  matches the "Set by you when you save" pill.
- A Re-sort can be started without its sheet:
  `MSYS_NO_PATHCONV=1 adb shell "run-as com.agentic.binning am start-foreground-service --user 0 -n com.agentic.binning/.pipeline.AnalysisService -a com.agentic.binning.action.START_ANALYSIS --es project_name '<name>' --es project_path '/storage/emulated/0/Agentic Binning/<name>' --es run_kind Resort --es resort_model Lfm3_1_7B --ez resort_keep_edits true"`.
