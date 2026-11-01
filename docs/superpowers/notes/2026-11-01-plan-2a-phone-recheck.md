# Plan 2a phone recheck — 2026-11-01

Code under test: `0371a18` on `plan-2a-sorting`. No production code changed.
Device: Galaxy S26 Ultra.

## Result

Local verification passed. Task 10 Step 6's authenticated Anthropic request
is still unverified. The user explicitly approved merging for personal use
with this test deferred, to unblock Plan 2b. This waives the earlier pre-merge
gate, not the test itself. No real API key was entered and no paid request was
made; live request shape and offline/reconnect behavior remain test debt.

| Check | Result / evidence |
| --- | --- |
| Debug app and test APK build | `assembleDebug assembleDebugAndroidTest` succeeded; both installed with `adb install -r -t` |
| JVM suite, forced execution | `testDebugUnitTest --rerun-tasks`: 403 tests, zero failures/errors/skips |
| Device DAO tests | `SortDaoTest`: 17 passed |
| Device Keystore tests | `ApiKeyStoreDeviceTest`: 3 passed; test alias only |
| Device migrations | `ProjectDatabaseMigrationTest`: 4 passed |
| Device database tests | `ProjectDatabaseTest`: 7 passed |
| Resume with only sorting left | Started categorizing 48 Analyzed scratch items without adding or re-analyzing media |
| Missing Claude key | Run 9: `unavailable`, zero assignments; fixed notice visible |
| Notice after cold launch | Same missing-key notice restored after force-stop/start; Resume enabled |
| Stop during local sorting | Run 10: `sort-v3`, 10/48 committed, 38 still Analyzed; `partial` / `Stopped`, `stoppedByUser=1` |
| Resume after Stop | Run 11: 38/38 leftovers sorted, `done`; 48 total Sorted, original 2 decode failures unchanged |
| Release | Logcat records LFM3-1.7B released after the resumed run |
| Names / category references | Zero Sorted scratch items missing a category or nonblank friendly name |
| FTS | `category : Nature` returns exactly the category's 13 item ids |
| Database integrity | Scratch database `PRAGMA integrity_check`: `ok` |
| Layout | Settings and Categorizing inspected in dark/light; header and counts fit; missing-key notice visible in dark; sorted Hawaii cards now show category badges |
| User project protection | Hawaii Photos before/after databases match byte-for-byte (53 Sorted items) |

Build warnings remain: Gradle deprecations, existing manifest
`extractNativeLibs` warning, and two ExperimentalCoroutinesApi opt-in warnings
in `AnalysisLauncherTest`. None was changed in this verification-only work.

## Scratch fixture and model quality

Backed up the existing synthetic **Sort Test** database, then reset only its
model-generated sorting in a copy with the app stopped. Kept analyses and the
2 existing Failed items; removed unreferenced model categories, refreshed
sorting-related FTS values, and set `stoppedByUser=1` so work required Resume.
Pushed the copy and read it back before testing. Hawaii and Maui were not used
as reset fixtures.

The resulting scratch categories are Nature (13), Food (15), Sports (6),
Transportation (5), Sandcastle (3), Subway Sign (1), Video (2), Photo (3).
This is a plumbing check, not a claim that LFM quality is resolved: media-kind
bins still occur within the allowed catch-all limit, and one category
description contains model self-talk. No new quality policy was introduced.
The original scratch backup remains available; the phone retains the newly
sorted scratch result and its verification runs.

## Resume decisions

- The user deferred the paid Claude test and asked whether their Nous Portal
  or X Premium subscription could substitute. The current app uses the official
  Anthropic Java SDK against Anthropic, not Portal or xAI. A separate provider
  integration would not validate that existing request path.
- **When returning to Claude, keep all 53 Hawaii Photos items and reset only
  their model-generated sorting.** Do not restore the old 46-item baseline over
  the current project. Back up again immediately before any future reset and
  preserve user-set names, assignments, and categories.
- The user approved merging `plan-2a-sorting` into `main` with the authenticated
  Claude test explicitly deferred for personal-use development. Keep Claude
  implemented and select LFM for daily use; disabling it is unnecessary.
- After the approved merge, integrate the 2b draft onto updated `main`, run its
  pure tests in the Android build, and resolve Task 6's actual 2a types before
  implementing Task 7 onward. Do not force-push/rewrite the shared 2b branch
  without separate approval.
- Renewed Nous Portal or X subscription usage does not provide credentials for
  this app's current direct Anthropic backend. The future live test needs an
  Anthropic API key with usable billing; another provider is separate scope.

## Local artifacts

All under `build/e2e/final-check/` (local, not committed):

- `device-tests.txt` — combined instrumented result, `OK (31 tests)`.
- `hawaii-current.db`, `hawaii-after.db` — unchanged 53-item project copies.
- `sorttest-before.db`, `sorttest-reset.db`, `sorttest-reset-readback.db`,
  `sorttest-stopped.db`, `sorttest-resumed.db`.
- `local-smoke-log.txt`, XML UI dumps, and screenshots:
  `settings-final-{dark,light}.png`, `categorizing-{dark,light}.png`,
  `notice-dark.png`. The early file `notice-light.png` actually captured dark
  before the theme transition completed; do not label it light evidence.
- `build/plan2a-verification-build.log`, `build/plan2a-unit-rerun.log` — build logs.

Hawaii backup SHA-256:
`6a4c082943655cccdfe3643f8236db999308c4b1d7345374d17a537f63e0b9e1`.

## Environment and phone left state

ADB was already available, but the SDK path from `local.properties` did not
exist. Restored command-line tools and the pinned Android 35 platform/build
tools at that configured path; no project build pins changed. Run instrumented
tests directly with `am instrument`, not a connected-test task that might
uninstall the app and remove downloaded models.

Phone left on Hawaii Photos, 53 Sorted items, LFM3-1.7B selected, frame cap 16,
Dark theme, no Claude key saved, Do Not Disturb off (its initial state). Both
Liquid model download directories remain present. Temporary UI dumps on
`/sdcard/avb-final-ui.xml`, installed test APK, and local backups were retained;
no media or model downloads were deleted.
