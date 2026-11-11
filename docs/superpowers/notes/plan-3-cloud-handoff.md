# Plan 3 cloud handoff

> Historical: the cloud session's ledger. The work was finished on the PC; the plan's status line and "Rulings made on the PC" are current. Its "Not built" and "Questions still open" no longer hold.

Ledger of the cloud session that planned and started Plan 3 on
`plan-3-binning` (Nov 4, 2026, no phone attached). Each entry is a ruling
(`Ruling: what — why — cost if wrong`) or a finished task
(`Task N: complete (commits …, tests: …)`). The summary for the next session
is below the ledger.

## Ledger

- Merged `origin/main` @ `738cfad` (PRs #22, #23) into `plan-3-binning` as `981766f`; a real merge, no rebase. Baseline: `testDebugUnitTest` 596 tests in 48 classes, 0 failures; `assembleDebug assembleDebugAndroidTest` succeed (after Maven 429 retries).
- Owner answers on Nov 4 (in conversation with the cloud session): B1 rename within a volume, B2 Pause and Stop after the current file, B3 resume on its own, B4 retry once then skip, B5 block unknown files with Add / Leave, B6, B9, B10 include binned items, B12, Q2's details, Q11 yes, P8 the owner's own copy design. Open: B7, B8, B11.
- Ruling: Status Binned is kept in step with the file (set by the engine; a Re-sort that changes a binned item's target sets it back) and "ready to bin" is decided by path — plan-2-notes asks for path, and the tile badge still needs a status — cost if wrong: one counting function.
- Ruling: The folder check ignores hidden files (names starting with ".") — they are never media, and blocking Bin Files on a `.nomedia` or a gallery cache would strand the user — cost if wrong: one filter in `FolderCheck`.
- Ruling: An item whose path another listed project also holds (possible only when one of them was off the list) is copied into its category folder and its original left in place — the owner's P8 answer says another project's files are copied, never moved — cost if wrong: the `keepSource` flag.
- Ruling: A rename whose check fails stops the run instead of skipping (B4 covers copies) — a rename can't be undone by skipping, and it means the storage misbehaves — cost if wrong: one branch in the engine.
- Ruling: A step started a third time (interrupted twice) is skipped with `interrupted` — B3 asks that a file that crashes the app can't loop; it mirrors analysis' attempts rule — cost if wrong: one constant.
- Ruling: A no-replace rename is `Files.move` without `REPLACE_EXISTING` after an existence check — Android's FUSE has no hard links for an atomic no-replace, and only this app writes into category folders — cost if wrong: a name clash in a sub-millisecond window, which the engine's post-rename check catches.
- Ruling: An I/O error from a rename that leaves the source where it was skips that file (`io`) and the run carries on — the owner chose skip for bad copies (B4), and an untouched source is the same situation — cost if wrong: one branch in `MoveEngine.rename`.
- Ruling: A recorded destination that vanished before its source was deleted points the item back at its source (`recordReverted`, step Failed) instead of deleting the only copy left — "never lose a file" outranks finishing the step — cost if wrong: one DAO method.
- Ruling: A first planning never claims a file it finds at the target when the source is gone (that item becomes Missing); only a resumed step whose destination has the planned size is treated as "renamed before the crash" — a stranger's identical file must not be adopted — cost if wrong: a test and a condition.
- Ruling: Project Media counts stay by status (Sorted = ready, Binned = binned) rather than by path — the engine sets Binned exactly when a file reaches its target and Re-sort takes it back (B10), so they agree; the planner still decides by path — cost if wrong: a predicate in `mediaCountsOf` (Task 11's original plan).
- Ruling: Remove from project also records a removed item that sits inside the project folder without being binned (one a Re-sort took out of binned) — otherwise the next folder check would block on its file — cost if wrong: one condition in `ProjectMediaRepository.removeItems`.
- Ruling: The report's in-place count and folder-check result live in memory (`BinFilesJob`), not the database — B8 (the report) is still open, and adding columns now could be wasted — cost if wrong: two `move_run` columns in a later schema.
- Ruling: The Analyze screen's status row shows "Binning files" / "Bin Files paused" with "12 of 248" in its counts slot while files move — the existing screen would otherwise say "Analyzing"; it is text only, no new UI — cost if wrong: two lines in `AnalyzeMapping`.
- Ruling: A move's Stop (notification or Analyze screen) only stops the move; it doesn't set analysis' remembered Stop — otherwise stopping a Bin Files would also stop analysis from auto-starting later — cost if wrong: one branch in `AppGraph.stopAnalysis`.
- Ruling: Bin Files started while the project still has an open run resumes that run instead of planning again — two runs over one set of files would race — cost if wrong: one line in `BinFilesJob.binFiles`.
- Task 1: complete (commit `4659b76`, tests: `MoveModelTest` 4).
- Task 2: complete (commit `4a4ed8b`, tests: `BinPlannerTest` 11).
- Task 3: complete (commit `ffd1f13`, tests: `FolderCheckTest` 10).
- Task 4: complete in the cloud (commit `d70568b`, tests: `ProjectSchemaTest` with v4; device `MoveDaoTest` 9 and `ProjectDatabaseMigrationTest` +2 written, not yet run on the phone).
- Task 5: complete in the cloud (commit `5012802`, tests: `RealMoveFilesTest` 8; device `MoveFilesDeviceTest` 4 written, not yet run on the phone).
- Task 6: complete (commit `f7d4e71`, tests: `MoveEngineTest` 30, Task 7's included).
- Task 7: complete in the cloud (commits `f7d4e71`, `98908d9`; the resume on project open is checked on the phone in Task 17).
- Task 8: complete (commit `46a5120`, tests: `MoveReportTest` 9).
- Task 9: complete in the cloud (commit `98908d9`, tests: `BinFilesJobTest` 13, `MoveJobTest` 9; the service and notification are checked on the phone).
- Task 10: step 1 complete (commit `6f38f63`); the Gallery and picker checks need the phone.
- Task 11: complete (commits `98908d9`, `a512463`, tests: `MediaStatusTest` +2, `MediaCountsTest`, `ProjectMediaViewModelTest` +1).
- Task 12: complete in the cloud (commit `831370b`; device tests written, not yet run on the phone).
- Task 13: complete in the cloud (commit `011f23c`; device tests written, not yet run on the phone).

## Summary for the owner and the next session

### Done, and how it was verified

Every task marked phone: no in `docs/superpowers/plans/2026-11-04-plan-3-binning.md`
(1–9, 11–13) and Task 10's code. Each was written test first (the test
seen failing), then `./gradlew testDebugUnitTest assembleDebug
assembleDebugAndroidTest` passed before its commit: **693 JVM tests in 56
classes, 0 failures** at the end (596 before Plan 3). Device tests compile
in `assembleDebugAndroidTest`; **none has run on a phone.**

What exists now:
- `move/`: the planner (`BinPlanner`), folder check (`FolderCheck`), file
  operations (`RealMoveFiles`), the engine (`MoveEngine`: rename within a
  volume, copy-check-delete across, Pause/Stop between files, resume from
  any recorded state), the job (`BinFilesJob`: check, plan, move, remove
  empty folders it made, check again), the report data and the MediaStore
  notifier.
- Schema v4 (`move_step` columns, `move_run` columns, `removed_file`), with
  `AutoMigration(3, 4)` and `4.json`.
- `PipelineController`: `binFiles` / `resumeMove` under `JobKind.Binning`,
  the `Moving` / `MovingPaused` phases; the service and notification;
  `AppGraph` resumes an interrupted run when a project opens.
- Re-sort reaches unlocked binned items (B10); Remove from project records
  removed binned files (Q2); Project Media's status line and edit guards
  while files move.

### Not built (and why)

- **No new UI.** B7 (confirm) and B8 (report) are open, and the owner hasn't
  approved the Plan 3 mockups yet. `Bin Files` on Project Media still shows
  its "later version" toast.
- Task 14 (Save moves binned files) and Task 15 (P8's copy into
  `Copied from <project>`) are phone: yes in the plan and not started.

### Read this before installing on the phone

- **Installing this build migrates every project the app opens to schema
  v4.** The migration only adds columns and a table, but the `main` build
  can't open a v4 database (Room refuses a downgrade). The P9 backup
  (`project.db.v3.bak`) is made only by Open project, not when a listed
  project opens at launch. **Back up each project's `.binning/project.db`
  first** (`adb pull`), especially Hawaii Photos and Maui. Whether every open
  should make the P9 backup is a question for the owner (recommend yes).
- Nothing moves a file unless Bin Files is started. Until Task 16 builds the
  sheet, start it over adb on a test project only (never Hawaii Photos or
  Maui):

```bash
MSYS_NO_PATHCONV=1 adb shell "run-as com.agentic.binning am start-foreground-service --user 0 -n com.agentic.binning/.pipeline.AnalysisService -a com.agentic.binning.action.START_ANALYSIS --es project_name '<name>' --es project_path '/storage/emulated/0/Agentic Binning/<name>' --es run_kind BinFiles"
```

### Questions still open for the owner

- **B7.** A confirm sheet before Bin Files (recommended: yes; mockup state 1).
- **B8.** What the report lists and where it stays (recommended: as in the
  questions document; mockup states 3 and 4).
- **B11.** Scanning both paths after each move (recommended, built); what
  Samsung Gallery and both pickers show is checked on the phone (plan
  Task 10 Step 2).
- **New:** should opening a listed project at launch make the P9 backup
  before a migration, like Open project does? (recommend yes; see above)
- The mockups (`docs/superpowers/mockups/2026-11-04-plan-3-binning.html`,
  7 states) need the owner's review before Task 16.

### Device tests to run on the phone

Build and install both APKs as in the plan's "Device tests", then run each
class by direct instrumentation (never `connectedDebugAndroidTest`):

```bash
adb shell am instrument -w -e class com.agentic.binning.db.MoveDaoTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb shell am instrument -w -e class com.agentic.binning.db.ProjectDatabaseMigrationTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb shell am instrument -w -e class com.agentic.binning.move.MoveFilesDeviceTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb shell am instrument -w -e class com.agentic.binning.db.SortDaoTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb shell am instrument -w -e class com.agentic.binning.projectmedia.ProjectMediaRepositoryTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

Regressions worth running too, since the schema changed:

```bash
adb shell am instrument -w -e class com.agentic.binning.db.ProjectDatabaseTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

```bash
adb shell am instrument -w -e class com.agentic.binning.project.ProjectOpenRemoveTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

New Plan 3 cases among them: `MoveDaoTest` (all 9),
`ProjectDatabaseMigrationTest#migrate3To4_keepsMoveRowsAndAddsDefaults` and
`#openProjectDatabase_migratesAV3File`, `MoveFilesDeviceTest` (all 4; needs
All files access), `SortDaoTest#prepareResort_includesUnlockedBinnedItems`
and `#prepareResort_keepEdits_leavesLockedBinnedItems`,
`ProjectMediaRepositoryTest#remove_binnedItem_recordsItsPath`,
`#remove_unbinnedItem_recordsNothing` and
`#remove_itemInsideTheProjectFolder_recordsItsPath`.

### Phone checks still to do

- Plan Task 10 Step 2: MediaStore rows after a move (one row, new path; did
  the `_id` survive the rename?), Samsung Gallery (timeline, Camera album,
  the category album, Favourites, Revert), both pickers, re-picking a binned
  file.
- Plan Task 17 (end to end): Bin Files on a fresh project, a force-stop half
  way and the resume (B3), `ls -lR` and `sha256sum` before and after, Pause
  and Stop, the folder check with a stray file, and (after Task 14) Save on a
  binned item.
- The notification while moving (title, Pause/Resume, Stop) and the wake
  lock released while paused.

### What the next session should do first

1. Read this file, the plan's status line and the questions document.
2. With the phone: back up the project databases, install, run the device
   tests above, then plan Task 10 Step 2 and Task 17 Steps 1–5 on a test
   project using the adb start.
3. Ask the owner to review the mockups and answer B7, B8 and the backup
   question; build Task 16's UI once approved.
4. Then Task 14 (Save moves) and Task 15 (P8 copies).

