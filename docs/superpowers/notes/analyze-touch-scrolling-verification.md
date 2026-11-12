# Analyze touch scrolling

Branch: `feat/analyze-touch-scrolling`, based on `0a720ca`.

## Behavior

- Swipe left/right across the Analyze carousel to select the next/previous item; no wrapping at either end.
- Selection drives the existing stored-analysis lookup, description, actions, tags, and backdrop.
- Reviewing stays on the selected item while pipeline progress changes. During a run, **Follow analysis** clears the review selection.
- Selection is scoped to its project path so matching item IDs in another project cannot select the wrong clip.
- The existing Description/output vertical scroll was retained, not replaced. Long descriptions, actions, and tags remain reachable by touch in both directions.
- The pipeline status uses the actual live item, not the reviewed card's media type.

## Executed verification

Physical device: Samsung SM-S948U1, Android API 37, ADB serial `R3GYC0JJDYD`.

- Observed RED: carousel UI test failed because no horizontal scrolling node existed; the original Description up/down test passed.
- Observed RED: selected-focus unit test expected index 0 but automatic analysis focus returned index 1.
- `testDebugUnitTest`: **884 tests, zero failures/errors/skips**, aggregated from JUnit XML.
- `assembleDebug` and `assembleDebugAndroidTest`: successful with the pinned Java 17 toolchain and existing SDK/model versions.
- Direct named instrumentation: `AnalyzeScrollingTest`, **OK (3 tests)**:
  - Horizontal scroll semantics exist.
  - Real touch swipes select matching saved output and stop at both ends.
  - A 60-line description scrolls forward and backward; its final action/tag and the top are reachable.
- `git diff --check`: clean.
- `lintDebug`: blocked by four existing errors, outside changed code: `ProjectOpenRules.kt:133` (`NewApi`), ignored `local.properties:1` (`PropertyEscape`), `GlassSheet.kt:176` (`ContextCastToActivity`), and `MainActivity.kt:107` (`StateFlowValueCalledInComposition`). The three tracked source expressions were verified in baseline HEAD. No errors remain in the new test or changed production files. Existing build warnings remain.

## Device safety and remaining scope

The installed `com.agentic.binning` release is not debuggable and was not replaced. Tests used a scratch Gradle init script adding `.scrolltest` to the debug application ID; both `com.agentic.binning.scrolltest` and its test APK remain installed. The init script is outside the repository. Synthetic composables only: no project was opened, no media was added/moved, and no model/provider call was made. The release APK path was unchanged afterwards. DND was restored and read back as `zen_mode=0`.

The tests exercise the real carousel/output composables with synthetic saved analysis, not a full database-backed project or live inference run. The owner requested committing/pushing the feature branch and installing the isolated `.scrolltest` app for manual testing. The release must remain installed and unchanged; do not merge or tag. The test app has separate settings/models; use **Open project** to review an existing project's saved results without reanalyzing it, and do not run Bin Files on real projects.

## Test harness compatibility

Compose BOM stays pinned. Its UI test library transitively requests Espresso 3.5.0, which fails on API 37 before test assertions because Android removed reflective `InputManager.getInstance`. A test-only explicit Espresso 3.7.0 dependency resolves that failure without changing app SDK or production dependencies.
