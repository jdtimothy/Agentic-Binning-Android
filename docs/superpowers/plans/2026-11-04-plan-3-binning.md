# Plan 3: Binning (moving files) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status: built, and checked on the phone, on branch `plan-3-phone`; not yet merged.** Tasks 1-13 were built in a cloud session (`docs/superpowers/notes/plan-3-cloud-handoff.md`), the rest on the PC with the phone attached. 834 JVM tests and 92 device tests pass on SM-S948U1. Left: B11's Gallery Revert and the Android photo picker (owner's look).

**Goal:** **Bin Files** moves every sorted item's file to `<project>/<Category>/<friendly name>.<original extension>`, and a **Save** that renames, re-categorizes or merges something already binned moves its files the same way, right away. No file is ever lost or overwritten, every run is resumable after a crash, and the category folders are checked before and after.

**Architecture:** Same single module and hand-wired `AppGraph`. The move engine lives in `move/` as plain Kotlin behind two interfaces, `MoveFiles` (the file system) and `MoveStore` (the database), so its rules run as JVM tests with fakes. A run is written to `move_run` / `move_step` in one transaction before anything moves, and every step's state is recorded, so a crash resumes where it stopped. Within one storage volume a move is an atomic rename, checked afterwards; across volumes it is a copy, checked by size and SHA-256, then the original is deleted (B1). Runs go through `PipelineController` and `ModelArbiter` (`JobKind.Binning`) and the foreground service, like Re-sort: refused, never queued, while another run is going; Pause and Stop act between files (B2).

**Tech Stack:** As Plan 2b (Kotlin 2.2.0, AGP 8.13.0, Compose Material 3, Haze, Coil 3, Room 2.7 with the bundled SQLite driver).

**Spec and design:**
- `docs/superpowers/specs/2026-10-28-agentic-binning-design.md`: "4. Bin Files (user-started) and Save moves", "Data model", "Storage and permissions", Goals, Non-goals, Anti-goals.
- Questions and answers: `docs/superpowers/mockups/2026-11-04-plan-3-questions.md`.
- Mockups: `docs/superpowers/mockups/2026-11-04-plan-3-binning.html` (open in a browser; 7 states), and 2b state 6 (Save in progress) in `2026-10-31-plan-2b-project-media.html`.
- Plan 2b: `docs/superpowers/plans/2026-10-31-plan-2b-project-media.md` ("Left for Plan 3", "Product decisions"), and `docs/superpowers/plan-2-notes.md`.
- The brief this plan was written from: `docs/superpowers/plan-3-brief.md`.

## Global Constraints

- Everything in Plan 1's and Plan 2b's Global Constraints still holds (device, pinned versions, build command, one model loaded at a time, project folder layout, schema migrations never destroy data, search through `FtsQuery.from`, category names through `CategoryNames`).
- **Files are never lost or overwritten.** At every moment each file exists at its source, at its checked destination, or both. A name already taken (on disk or earlier in the plan) gets ` 2`, ` 3` and so on, ignoring case. No undo.
  - Same volume (B1, owner's choice over the brief's copy rule): rename, then check the file is at the target with the same size and gone from the source.
  - Across volumes: copy to `<project>/.binning/moving/<step id>.tmp` hashing the source as it is read, check size and SHA-256 of the copy read back, rename it into place, record it, then delete the original.
  - The only deletes the engine makes: its own temp copies, a source whose checked copy is recorded, and an empty folder it made (B12).
- **Resumable.** The plan is written in one transaction before the first move. A step's state is written after each phase. An interrupted run resumes on its own when the project opens (B3). A step started twice without finishing is skipped, never retried forever.
- **Decide by path, not status alone** (plan-2-notes). `Binned` means the file is at its target; the planner compares `currentPath` with the target and fixes the status both ways.
- **Remove from project never deletes a file** (Q1). A removed binned item's path goes in `removed_file` (Q2). Failed and Missing items are never moved and never block Bin Files.
- **Never remove a folder the app didn't make** (B12): only empty folders directly inside the project that a finished step put a file in, or a `Copied from <project>` folder (P8). A plain directory delete, which fails if anything is inside.
- **Another project's files are never moved** (P8). Files another project holds are copied in, never taken.
- **One job at a time.** Bin Files and Save moves are `JobKind.Binning` under `ModelArbiter`, refused with a reason while any run is in progress, never queued. While files move, Add media, switching or removing the open project, Resume analysis, Re-sort, Categorize now and every Save are off with their reason (Q11).
- **Schema v4 only through `AutoMigration(3, 4)`**, with the exported `schemas/.../4.json`, its hash in `PROJECT_DB_IDENTITY_HASHES`, and a `MigrationTestHelper` test.
- **No UI before its mockup is approved.** B7 (confirm, state 1) and B8 (report, states 3 and 4) are approved.
- **No Claude API calls.** Nothing here is model-facing.
- **Device tests run by direct instrumentation**, never `connectedDebugAndroidTest` (see "Device tests").

## Review Focus

1. **No file is lost or overwritten on any path**, including a crash between any two phases. Tests: Task 6 `rename_destTaken_takesTheNextNumber`, `copy_neverOverwrites`; Task 7 `resume_*` (one per phase); device Task 5 `MoveFilesDeviceTest`.
2. **A copy is only trusted after it is read back.** Tests: Task 6 `copy_mismatchTwice_skipsAndKeepsTheSource`, `copy_spaceRunsOut_deletesTheHalfCopy`.
3. **Pause and Stop never leave a file half moved.** Tests: Task 6 `pause_finishesTheCurrentFileFirst`, `stop_finishesTheCurrentFileFirst`, `cancel_finishesTheCurrentStep`.
4. **The folder check never blocks on a missing file and always blocks on an unknown one** (B5). Tests: Task 3.
5. **Nothing runs alongside a move.** Tests: Task 9 `binFiles_isRefusedWhileARunIsInProgress`, `start_isRefusedWhileFilesMove`.
6. **Another project's file is never moved or deleted** (P8). Tests: Task 2 `sharedItem_isCopiedNotMoved`, Task 6 `keepSource_neverDeletesTheSource`, Task 15.

## Device tests

`connectedDebugAndroidTest` uninstalls the app afterwards, which deletes the downloaded models. Never use it. Build both APKs, install them, and run one class at a time by direct instrumentation (from Git Bash on the PC, with `CLAUDE.md`'s `JAVA_HOME`):

```bash
JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot" ./gradlew assembleDebug assembleDebugAndroidTest
```

```bash
adb install -r -t build/outputs/apk/debug/app-debug.apk
```

```bash
adb install -r -t build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

```bash
adb shell am instrument -w -e class com.agentic.binning.db.MoveDaoTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

Every device test written in the cloud is marked **written, not yet run on the phone** until it runs on an identified device (`adb devices -l`). Compiling `assembleDebugAndroidTest` is not running it. Device fixtures live under the app's external files dir or a fresh `Movies/avb-plan3-test/` folder and are deleted afterwards; never a user project.

## Product decisions

Full text, choices and reasons: `docs/superpowers/mockups/2026-11-04-plan-3-questions.md`.

### Decided Nov 4, 2026 (owner)

- **B1. Rename within a volume; copy, check, delete across volumes.** The owner chose this over the brief's "copy, verify, update, delete" rule. A rename moves the file to its new folder and name in one atomic step.
- **B2. Pause and Stop** on the progress card and in the notification. Each finishes the file in progress, then pauses or stops before the next file. This replaces the spec's "Pause, Stop … disabled while it runs".
- **B3. A run the app didn't finish resumes on its own** when the project opens, finishing the step in flight first; paused if it was paused. No boot receiver.
- **B4. A copy that doesn't match its original twice is skipped**, its source untouched and still ready to bin; the run carries on, and the report lists it. (A rename whose check fails stops the run: it can't be skipped.)
- **B5. The folder check runs before and after.** A missing binned file marks the item Missing and never blocks. A file in a category folder that no item points to (and that wasn't removed) blocks before anything moves, with **Add to project** and **Leave them**.
- **B6. Storage full:** before each copy, free space must be at least the file's size plus 200 MB; a copy that runs out is deleted and the run stops with "Free up N GB". Every run stops under 50 MB free.
- **B9. Bin Files is off when every sorted item is in place**; a plan with nothing to move just marks in-place items binned and reports "Nothing to move".
- **B10. Re-sort reaches unlocked binned items**, as the spec says; their files move at the next Bin Files. Changes `SortDao.prepareResort` and 2b Task 9's note.
- **B12. Only empty folders the app made are removed**; source folders never are.
- **Q2 (details). `removed_file(path, removedAt, reason)`** filled by Remove from project for items in category folders and by Leave them; rows are dropped when the file is added again or is gone; a renamed or merged category's old folder is kept while it holds a removed file.
- **Q11. The user may leave the screen** while files move.
- **P8. Media another project holds** (owner's own design): any file inside another project's folder (listed or not, found by `.binning/project.db`) or held by a listed project by hash or path. Add a folder and Pick photos and videos show one notice naming the projects and counts, with **Cancel** (the rest of the batch is still added) and **Copy media files**, which copies them, checked, into `<project>/Copied from <other project>/` with progress in the notice. Copies are ordinary items afterwards and are binned like any other.

### Decided Nov 3, 2026, local time (owner, on the PC session)

- **B7. A confirm sheet before Bin Files**, as in mockup state 1.
- **B8. The report as drawn** in mockup states 3 and 4.
- **Every open of an older database makes the backup**, not only Open project: `openProjectDatabase` copies it to `project.db.v<N>.bak` first, so a listed project opening at launch, or another listed project opened during an add, is covered too (`ffd2e05`).
- **The end-to-end test sorts with LFM3-1.7B.** Settings is switched from Claude for the test and switched back afterwards; no paid call.
- **Mockup review.** No status pills on the progress card (no Moving/Checking/Copying chips: `MoveProgress.phase` isn't shown). The copy dialog stays put and updates only its bar, with no per-file line; the progress card likewise never redraws. The progress card, sheets and dialogs use the approved canvas's glass (55% dark, 58% light, 26 px blur), not 2b's near-opaque panels. In the app, today's `GlassSheet` and dialogs paint a solid `tokens.bg` behind the glass. In dark mode a sheet is the lighter glass (`rgba(72,78,96,.55)`) and the cards inside it the darker (`rgba(10,12,18,.46)`, warnings `rgba(52,36,12,.62)`); a dialog with no cards inside, like the P8 notice, is the darker glass itself; light mode is unchanged.

### Checked on the phone

- **B11. MediaStore after a move:** one row at the new path and none at the old, with `_id` and `date_modified` kept. The Camera album's count drops and the category folder appears as an album. A Gallery Favourite survives the move. Re-picking a binned file says "already in this project". Not checked: Gallery's Revert on a photo edited before the move, and the Android photo picker (the phone is set to Samsung Gallery's).

### Decided after the build (owner)

- **Every sheet gets the same glass:** the Plan 2b sheets, Filters and the project sheet now share `GlassSheet` and `GlassDialog` (`ui/media/GlassSheet.kt`), drawn in the screen's window, on branch `glass-sheets`. Settings' one confirm dialog is still its own window.
- **The report keeps its Failed, Missing and Skipped pills.**

### Rulings made on the PC

- **The Bin Files sheets and the P8 notice draw in the screen's own window**, not a dialog's: Haze can only blur its own window, and this phone has no cross-window blur. The project sheet closes when the notice opens, as mockup state 7 draws it.
- **Schema v5** (`move_run.inPlace`, `checkOk`, `reportClosed`) keeps a report until it is closed, across a restart (B8). v4 was already on the phone, so it is a migration, not an edit.
- **A stopped run runs the folder check too**, since state 4 reports it.
- **Every open of an older database backs it up first**, in `openProjectDatabase`, because three paths can migrate one (launch, Open project, and another listed project during an add).
- **Notifications and the card are paced** (at most 2 and 4 updates a second, always ending on the latest): Android sheds a package's updates over 5 a second, which once left a paused run showing a Pause button.
- **A Save that would move files while analysis runs is refused and rolled back** with its reason, never queued. **What a Save will move reads Sorted until it arrives**, so a stopped Save offers Bin Files rather than hiding behind "All files binned".
- **A category rename is one move per file**, not a folder rename, so each file gets the same checks and crash resume.
- **A dropped category's folder is removed only once empty**; a removed file keeps it (Q2).
- **A held file found on disk reads "(not on your list)"**; `Registrar` reaches the file's folder before hashing it, so nothing is hashed that won't be added.
- **Left-out rows show an icon, not a thumbnail**; a Failed or Missing file often has no picture.

---

## File Structure

All under `src/main/java/com/agentic/binning/` unless noted. Tests mirror it under `src/test/java/...` (JVM) and `src/androidTest/java/...` (device).

| File | Responsibility | Task |
| --- | --- | --- |
| `move/MovePlanner.kt` | Target paths, " 2" suffixes, in place (modify: `isInPlace`, `nextFree` public) | 2 (done in 2b, extended) |
| `move/LayoutCheck.kt` | Missing and unexpected files (2b) | — |
| `move/MoveModel.kt` | Run and step kinds and states, `MoveProgress`, `MoveOutcome` | 1 |
| `move/BinPlanner.kt` | Which items move, stay, are left out or become Missing; the steps | 2 |
| `move/FolderCheck.kt` | B5's check over `LayoutCheck`: expected, missing, extra; hidden files ignored | 3 |
| `db/Entities.kt`, `db/ProjectDatabase.kt`, `db/MoveDao.kt` | Schema v4 (`move_step` and `move_run` columns, `removed_file`); the DAO | 4 |
| `move/MoveFiles.kt` | File system interface and `RealMoveFiles` (rename without overwrite, hashing copy, volume check, free space) | 5 |
| `move/MoveEngine.kt` | Runs a written plan step by step: rename or copy, check, record, retry or skip, Pause and Stop | 6 |
| `move/MoveEngine.kt` (`recover`) | Finishing a step found mid-way after a crash | 7 |
| `move/MoveReport.kt` | The report's numbers and lists from a run and its steps | 8 |
| `pipeline/PipelineController.kt`, `pipeline/ModelArbiter.kt`, `pipeline/AnalysisService.kt`, `pipeline/AnalysisLauncher.kt` | `Moving` phases, `binFiles`, Pause / Stop between files, `RunKind.BinFiles` | 9 |
| `move/BinFilesJob.kt`, `AppGraph.kt` | Orchestration: folder check, plan, write, run, remove empty folders, check again; resume on open | 9, 7 |
| `move/MediaStoreNotifier.kt` | Scan both paths after each step (B11) | 10 |
| `projectmedia/MediaStatus.kt`, `ui/media/ProjectMediaViewModel.kt` | Counts by path, `ActiveJob.Binning` / `Saving` with Paused, reasons; Bin Files state | 11 |
| `db/SortDao.kt` | Re-sort reaches unlocked binned items (B10) | 12 |
| `projectmedia/ProjectMediaRepository.kt`, `db/ProjectMediaDao.kt` | Remove records `removed_file`; Leave them | 13 |
| `projectmedia/ProjectMediaRepository.kt`, `move/BinPlanner.kt` | Save on binned items writes edit and plan together | 14 |
| `media/Registrar.kt`, `media/ProjectFinder.kt`, `AppGraph.kt` | P8: find project folders on disk; copy into `Copied from <other>/` | 15 |
| `ui/media/BinSheets.kt`, `ui/media/MoveCard.kt`, `ui/project/*` | Confirm, progress card, report, unknown files, resume banner, P8 notice | 16 |

---

## Tasks

### Task 1: Move vocabulary · phone: no

**Files:** Create `move/MoveModel.kt`. Test: `src/test/java/com/agentic/binning/move/MoveModelTest.kt`.

**Interfaces:**
- `enum class MoveKind { Bin, Save }`, stored as its name in `move_run.kind`.
- `enum class RunState { Running, Paused, Stopped, Done, Failed }` (`move_run.state`). `isOpen` = Running or Paused: a run to resume.
- `enum class StepState { Planned, Copied, Verified, Done, Skipped, Missing, Failed }` (`move_step.state`). `Copied` = the temp copy is complete; `Verified` = the file is at its destination and recorded, the source may still exist; `Done`, `Skipped`, `Missing`, `Failed` are final.
- `MoveProgress(kind, done, total, current: String?, phase: MovePhase?, paused: Boolean)`, `enum class MovePhase { Moving, Copying, Checking, Placing, DeletingOriginal }`.
- `sealed interface MoveOutcome`: `Done`, `Stopped`, `StorageFull(neededBytes)`, `Failed(message)`, `Blocked(problems: LayoutProblems)` (the check before), `NothingToMove(inPlace)`.
- State names parse back with a fallback (`StepState.of("…") ?: Failed`), so an unknown value from a newer build never crashes the resume.

- [x] **Step 1: Test** `states_roundTripTheirNames`, `unknownState_isFailedNotACrash`, `openRuns_areRunningAndPaused`. Run `./gradlew testDebugUnitTest --tests '*MoveModelTest'`. Expected: fails (no class), then passes.
- [x] **Step 2: Implement.**
- [x] **Step 3: Commit** `feat(move): run and step states`.

**Done Nov 4** (`4659b76`): `MoveModelTest` 4 tests.

### Task 2: Bin planner · phone: no

**Files:** Create `move/BinPlanner.kt`; modify `move/MovePlanner.kt`. Test: `src/test/java/com/agentic/binning/move/BinPlannerTest.kt`.

**Interfaces:**
- `data class BinItem(id, status: ItemStatus, categoryId: Long?, friendlyName: String?, currentPath: String, originalName: String)`.
- `data class LeftOut(itemId, reason: LeftOutReason)`; `enum class LeftOutReason { Failed, Missing, NoCategory }`.
- `data class PlannedStep(itemId, source, dest, keepSource: Boolean, numbered: Boolean)`.
- `data class BinPlan(steps, inPlace: List<Long>, becameMissing: List<Long>, leftOut: List<LeftOut>)`.
- `BinPlanner.plan(projectDir, items, categoryNames: Map<Long, String>, existingPaths: Set<String>, exists: (String) -> Boolean, sharedPaths: Set<String>, only: Set<Long>? = null): BinPlan`.
  - Eligible: Sorted or Binned with a category. Failed and Missing are left out with their reason. Pending, Analyzing and Analyzed never reach here (Bin Files is off), and are left out as `NoCategory` if they do.
  - An eligible item whose `currentPath` doesn't exist becomes Missing (`becameMissing`), unless it is already at its target.
  - In place (by path, `MovePlanner.isInPlace`): listed in `inPlace` (marked Binned), never moved.
  - The rest go through `MovePlanner.plan`; `numbered` when the destination isn't the plain target.
  - `keepSource` when `currentPath` is in `sharedPaths` (another listed project holds the same path, P8's follow-on ruling).
  - `only` limits the plan to those items (a Save).
- `MovePlanner.isInPlace(projectDir, request)` and `MovePlanner.nextFree(dest, taken: (String) -> Boolean)` become public (the engine uses `nextFree` for a name taken since planning).

- [x] **Step 1: Tests:** `sortedItems_getStepsInIdOrder`, `inPlace_isMarkedNotMoved`, `binnedButMoved_isPlannedAgain` (B10), `failedAndMissing_areLeftOutWithTheirReason`, `goneSource_becomesMissing`, `goneSourceButAtTarget_isInPlace`, `takenName_isNumbered`, `sharedItem_isCopiedNotMoved`, `only_limitsThePlan`, `nextFree_skipsEveryTakenNumber`. Expected: fail, then pass.
- [x] **Step 2: Implement.**
- [x] **Step 3: Commit** `feat(move): bin planner`.

**Done Nov 4** (`4a4ed8b`): `BinPlannerTest` 11 tests (`goneSourceButAtTarget_isInPlace` became `goneBinnedFile_becomesMissing`: a first planning never claims a file at the target; the engine's resume does that, Task 7; `extension_comesFromTheOriginalName` added).

### Task 3: Folder check · phone: no

**Files:** Create `move/FolderCheck.kt`. Test: `src/test/java/com/agentic/binning/move/FolderCheckTest.kt`.

**Interfaces:**
- `FolderCheck.check(projectDir, categoryNames: Collection<String>, items: List<CheckItem>, removed: Collection<String>, filesOnDisk: Collection<String>): LayoutProblems`; `CheckItem(path, binned: Boolean)`.
  - Category folders are `MovePlanner.folderFor(name)` of every category.
  - `missing`: binned items whose path isn't on disk (the caller marks them Missing: never blocking, B5).
  - `unexpected`: files in category folders that no item points to (binned or not) and that aren't in `removed` (blocking, B5).
  - Files whose name starts with `.` are ignored (ruling: a hidden file is never media; B12 still won't delete a folder holding one).
- `FolderCheck.categoryFiles(projectDir, categoryNames, list: (dir) -> List<String>)`: the files to pass as `filesOnDisk`, recursive.

- [x] **Step 1: Tests:** `clean_isOk`, `missingBinned_isReportedNotBlocking`, `unknownFile_isUnexpected`, `removedFile_isSkipped`, `unbinnedItemInACategoryFolder_isExpected`, `hiddenFiles_areIgnored`, `filesOutsideCategoryFolders_areIgnored`, `caseDiffers_stillMatches`.
- [x] **Step 2: Implement** over `LayoutCheck` (binned paths = binned items; ignored = removed plus every other item's path).
- [x] **Step 3: Commit** `feat(move): folder check before and after`.

**Done Nov 4** (`ffd1f13`): `FolderCheckTest` 10 tests.

### Task 4: Schema v4 and the move DAO · phone: no (device tests run later)

**Files:** Modify `db/Entities.kt`, `db/ProjectDatabase.kt` (v4, `AutoMigration(3, 4)`, `abstract fun moves(): MoveDao`), `db/ProjectSchema.kt` (v4 hash). Create `db/MoveDao.kt`, `schemas/com.agentic.binning.db.ProjectDatabase/4.json` (exported by the build). Test: `src/androidTest/java/com/agentic/binning/db/MoveDaoTest.kt`, `ProjectDatabaseMigrationTest.migrate3To4_*`, `ProjectSchemaTest` (JVM).

**Schema v4** (every new column has a default, so `AutoMigration` adds it):
- `move_step`: `bytes INTEGER NOT NULL DEFAULT 0` (source size at planning; resume compares it), `attempts INTEGER NOT NULL DEFAULT 0` (starts of the step, B3), `keepSource INTEGER NOT NULL DEFAULT 0` (P8), `numbered INTEGER NOT NULL DEFAULT 0`, `error TEXT` (why Skipped or Failed).
- `move_run`: `message TEXT` (why it stopped), `removedFolders TEXT` (newline-separated, for the report).
- `removed_file(path TEXT PRIMARY KEY, removedAt INTEGER NOT NULL, reason TEXT NOT NULL)`; `reason` is `removed` (Q2) or `left` (B5's Leave them).

**`MoveDao`:**
- `writeRun(run, steps): Long` and `markInPlace(ids)`, `markMissing(ids)`: one `@Transaction` writing the run, its steps, the in-place items (Binned) and the vanished ones (Missing).
- `openRun(): MoveRunEntity?` (Running or Paused, newest), `run(id)`, `steps(runId)`, `setRunState(id, state, message?)`, `finishRun(id, state, finishedAt, message?, removedFolders?)`.
- `startStep(id): Int` (increments and returns `attempts`), `setStepState(id, state, error?)`, `setStepDest(id, dest)`.
- `recordMoved(stepId, itemId, dest, hash?, state)`: one transaction: `item.currentPath = dest`, `status = 'Binned'`, `contentHash = hash` unless another item has it, and the step's state.
- `recordMissing(stepId, itemId)`: item Missing, step Missing.
- `binnedFolders(): List<String>`: the parent folders of every Done step's destination (B12's "a folder the app made").
- `removedPaths()`, `addRemoved(paths, reason, at)`, `dropRemoved(paths)`.
- Items and categories for the planner and check: `binItems()`, `categoryNames()`.

- [x] **Step 1: Write the device tests** (written, not yet run on the phone): `MoveDaoTest`: `writeRun_isOneTransaction`, `recordMoved_updatesItemAndStepTogether`, `recordMoved_keepsHashOnClash`, `startStep_countsAttempts`, `openRun_findsRunningAndPaused`, `binnedFolders_areDoneDestinationsOnly`, `removedPaths_addAndDrop`. `ProjectDatabaseMigrationTest`: `migrate3To4_keepsMoveRowsAndAddsDefaults`, `openProjectDatabase_migratesAV3File`.
- [x] **Step 2: Implement**; build to export `4.json`; add its hash to `PROJECT_DB_IDENTITY_HASHES` (`ProjectSchemaTest` checks it).
- [x] **Step 3:** `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`. Expected: BUILD SUCCESSFUL; `ProjectSchemaTest` passes with v4.
- [x] **Step 4: Commit** `feat(db): schema v4 for moves and removed files`.
- [x] **Step 5 (phone):** run `MoveDaoTest` and `ProjectDatabaseMigrationTest`. Expected: OK.

**Done Nov 4** (`d70568b`, `recordReverted` added in `f7d4e71`): v4 hash `e2915b6307ad16155355e32e657395f2`; `ProjectSchemaTest` passes. `MoveDaoTest` (9) and `ProjectDatabaseMigrationTest.migrate3To4_*` (2): **written, not yet run on the phone**.

### Task 5: File operations · phone: no (device test runs later)

**Files:** Create `move/MoveFiles.kt`. Test: `src/test/java/com/agentic/binning/move/RealMoveFilesTest.kt` (JVM, temp directories), `src/androidTest/java/com/agentic/binning/move/MoveFilesDeviceTest.kt`.

**Interfaces:**
- `interface MoveFiles`: `exists`, `size`, `lastModified`, `setLastModified`, `sameVolume(a, b)`, `renameNoReplace(from, to): Boolean` (false if `to` exists; never overwrites), `copyHashing(from, to): String` (SHA-256 of the bytes read; throws `IOException`, `NoSpaceException` for ENOSPC), `sha256(path)`, `delete(path)`, `mkdirs(dir)`, `usableSpace(dir)`, `listFiles(dir)` (recursive), `removeEmptyDir(dir): Boolean`.
- `class RealMoveFiles(volumeOf: (File) -> Any?)`: `java.nio.file.Files.move` without `REPLACE_EXISTING` after an existence check (FUSE has no link-based no-replace rename; the window is closed by the engine re-checking the destination). `volumeOf` is `Os.stat(path).st_dev` on Android and `Files.getFileStore` on the JVM.

- [x] **Step 1: JVM tests:** `rename_neverReplaces`, `copyHashing_returnsTheSourceHash`, `removeEmptyDir_keepsANonEmptyFolder` (a hidden file counts), `listFiles_isRecursive`, `sameVolume_onOneTempDir`.
- [x] **Step 2: Device test** (written, not yet run on the phone): `MoveFilesDeviceTest`: rename within `/storage/emulated/0` keeps size, last-modified and content; `sameVolume` true inside shared storage and false against the app's internal files dir; ENOSPC can't be forced, so only the type is checked.
- [x] **Step 3: Implement. Commit** `feat(move): file operations`.

**Done Nov 4** (`5012802`): `RealMoveFilesTest` 8 tests on the JVM. `MoveFilesDeviceTest` (4): **written, not yet run on the phone** (needs All files access granted).

### Task 6: Move engine · phone: no

**Files:** Create `move/MoveEngine.kt`, `move/MoveStore.kt`. Test: `src/test/java/com/agentic/binning/move/MoveEngineTest.kt` with `FakeMoveFiles` and `FakeMoveStore` (`src/test/.../move/MoveFakes.kt`).

**Interfaces:**
- `interface MoveStore` (implemented over `MoveDao` in Task 9): `steps(runId)`, `startStep`, `setStepState`, `setStepDest`, `recordMoved`, `recordMissing`, `setRunState`.
- `class MoveControl`: `pause()`, `resume()`, `stop()`; `StateFlow` of the request. The engine reads it **only between files**.
- `fun interface MoveNotifier { fun moved(from: String?, to: String) }` (`from` is null when the source stays, P8; Task 10 implements it).
- `class MoveEngine(store, files, notifier, projectDir, clock)`: `suspend fun run(runId, control, onProgress): MoveOutcome`.

**Rules per step** (from Planned; Task 7 covers the other states):
1. Before the step: Stop → `Stopped`. Pause → run Paused, wait, then Running. Free space under 50 MB → `StorageFull`. `startStep`: a third start → Skipped `interrupted`.
2. Source gone → `recordMissing`, carry on.
3. Destination taken since planning → `nextFree`, `setStepDest`.
4. **Rename** (same volume, not `keepSource`): `mkdirs`, `renameNoReplace`; then check target exists with the source's size and source gone → hash the target → `recordMoved(Done)`. A failed check → step Failed, run `Failed` (B4: a rename can't be skipped).
5. **Copy** (other volume, or `keepSource`): free space < size + 200 MB → `StorageFull(size + 200 MB − free)` with the step still Planned. `copyHashing` to `.binning/moving/<step id>.tmp`, copy last-modified, step Copied; read the temp back: size and SHA-256 must match. Mismatch → delete temp, copy once more; again → delete temp, step Skipped `copy-mismatch`, carry on (B4). ENOSPC mid-copy → delete temp, step Planned, `StorageFull`. Match → `renameNoReplace(temp, dest)` (taken → `nextFree`), `recordMoved(Verified)`, then delete the source unless `keepSource`, step Done.
6. `notifier.moved(source, dest)` after each Done step (nothing for a `keepSource` copy's source, which stays).
7. Each step runs under `NonCancellable`: cancelling the coroutine (service destroyed, project switch) stops after the current file, like Stop.

- [x] **Step 1: Tests:** `rename_movesAndRecords`, `rename_destTaken_takesTheNextNumber`, `rename_checkFails_failsTheRun`, `copy_acrossVolumes_movesAndDeletesTheSource`, `copy_neverOverwrites`, `copy_mismatchOnce_retriesAndSucceeds`, `copy_mismatchTwice_skipsAndKeepsTheSource`, `copy_spaceShort_stopsBeforeCopying`, `copy_spaceRunsOut_deletesTheHalfCopy`, `keepSource_neverDeletesTheSource`, `missingSource_marksMissingAndCarriesOn`, `lowSpace_stopsEveryRun`, `pause_finishesTheCurrentFileFirst`, `stop_finishesTheCurrentFileFirst`, `cancel_finishesTheCurrentStep`, `thirdStart_isSkipped`, `progress_countsEveryStep`, `notifier_getsBothPaths`.
- [x] **Step 2: Implement.**
- [x] **Step 3:** `./gradlew testDebugUnitTest`. Expected: all pass.
- [x] **Step 4: Commit** `feat(move): move engine`.

**Done Nov 4** (`f7d4e71`): `MoveEngineTest` 30 tests, Task 7's included. Added beyond the list: `rename_ioError_skipsAndCarriesOn` (an I/O error that leaves the source untouched skips the file), `stop_whilePaused_endsWithoutMovingMore`. `MoveStore.recordReverted` covers a recorded destination that vanished while its source is still there.

### Task 7: Resume after a crash · phone: no (wiring checked on the phone in Task 17)

**Files:** `move/MoveEngine.kt` (`recover`), `move/BinFilesJob.kt` (`resumeOpenRun`), `AppGraph.kt` (call on project open). Test: `MoveEngineTest.resume_*`, `BinFilesJobTest.resume_*`.

**Rules** for a step found in each state when an open run resumes:
- **Planned:** delete its temp copy if any. Source there → run it normally. Source gone and the destination there with the planned `bytes` → the rename happened before the crash: hash, `recordMoved(Done)`. Both gone → Missing.
- **Copied:** delete the temp copy (it was never checked), back to Planned, run normally.
- **Verified:** the file is at its destination and recorded; delete the source if it still exists (unless `keepSource`) and the destination is there with the right size; Done.
- Final states: nothing.
- A Paused run resumes Paused (B2): the card shows Resume and Stop.
- `AppGraph` calls `resumeOpenRun` after a project opens and before `autoStart` (analysis waits for the move).

- [x] **Step 1: Tests:** `resume_planned_redoesTheMove`, `resume_planned_renameAlreadyDone_recordsIt`, `resume_copied_discardsTheTempAndRedoes`, `resume_verified_deletesTheSourceOnly`, `resume_verified_keepSource_keepsIt`, `resume_bothGone_marksMissing`, `resume_pausedRun_staysPaused`.
- [x] **Step 2: Implement** the engine part; `AppGraph` wiring with Task 9.
- [x] **Step 3: Commit** `feat(move): resume an interrupted run`.

**Done Nov 4** (`f7d4e71` engine, `98908d9` wiring): also `firstStart_sourceGoneButTargetThere_isMissing`, `resume_verified_isFinishedEvenAfterTwoStarts`, `resume_verified_destGone_keepsTheSource`. `AppGraph.resumeOpenMove` runs before analysis' auto-start on project open and app resume. Checked on the phone in Task 17.

### Task 8: Report data · phone: no

**Files:** Create `move/MoveReport.kt`. Test: `src/test/java/com/agentic/binning/move/MoveReportTest.kt`.

**Interfaces:** `MoveReport.of(run, steps, folderCount, leftOut: List<LeftOutRow>, check: LayoutProblems?)` → `MoveReport(kind, outcome, moved, copiedShared, skipped: List<SkippedRow>, numbered: List<String>, becameMissing, removedFolders, keptFolders, stillToMove, folderCheckOk, headline)`. Headline examples: "248 files binned into 14 category folders", "Nothing to move. 12 files were already in place.", "Bin Files stopped: storage is full". The layout depends on B8 (open); the numbers don't.

- [x] **Step 1: Tests:** `done_headlineCountsFiles`, `stopped_saysHowManyAreLeft`, `skipped_areListedWithWhy`, `nothingToMove_headline`, `numbered_listsTheNames`.
- [x] **Step 2: Implement. Commit** `feat(move): report data`.

**Done Nov 4** (`46a5120`): `MoveReportTest` 9 tests. `BinFilesJob.report(runId)`; the in-place count and the check result are kept in memory only (lost on restart; fine until B8 is settled).

### Task 9: Bin Files as a pipeline job · phone: no (service checked in Task 17)

**Files:** Modify `pipeline/PipelineController.kt`, `pipeline/AnalysisService.kt`, `pipeline/AnalysisLauncher.kt` (`RunKind.BinFiles`, `RunKind.ResumeMove`), `AppGraph.kt`. Create `move/BinFilesJob.kt`. Test: `PipelineControllerTest` (`BinFilesJobTest` with fakes).

**Interfaces:**
- `PipelinePhase.Moving` and `PipelinePhase.MovingPaused` (both `isRunning`; `canPause` only for `Moving`). `PipelineState` gains `moveKind`, `moveDone`, `moveTotal`, `moveCurrent`, `moveNotice` (why the last move stopped).
- `PipelineController.binFiles(project): MoveStart` (`Started` / `Refused(reason)`): refused while `phase.isRunning` ("Bin Files is available when analysis is stopped or done") and for a project that is not open. It launches one run that holds `arbiter.withJob(JobKind.Binning)` and calls `BinFilesJob.run`. `resumeMove(project)` does the same for an open run (B3), skipping the check and plan.
- `pause()` while `Moving` asks the `MoveControl` to pause; `resume()` while `MovingPaused` resumes it; `stop()` while moving asks it to stop (never cancels mid-file). `stopAndReset` (a project switch) is refused in the UI while files move (Q11); if it happens anyway, cancellation ends after the current step.
- `start()` and `categorizeNow()` while files move are ignored with a log line (the UI disables them: Q11), never queued behind the move.
- `BinFilesJob.run(project, control, onProgress): MoveOutcome`: folder check (B5: missing → Missing; unknown → `Blocked`, nothing written) → `BinPlanner.plan` → `MoveDao.writeRun` (or `NothingToMove`) → `MoveEngine.run` → remove empty folders the app made (B12) → folder check again → `finishRun`.
- The service maps `Moving` to "Binning <project> · 12 of 248" / "Saving · 2 of 3 files", with Pause/Resume and Stop actions; the wake lock is held while Moving and released while MovingPaused.

- [x] **Step 1: Tests:** `binFiles_isRefusedWhileARunIsInProgress`, `binFiles_runsUnderTheBinningJob`, `start_isRefusedWhileFilesMove`, `pause_and_resume_actBetweenFiles`, `stop_endsAfterTheCurrentFile`, `binFilesJob_unknownFiles_blocksBeforeWriting`, `binFilesJob_missingBinned_isMarkedAndCarriesOn`, `binFilesJob_removesOnlyEmptyFoldersItMade`, `binFilesJob_nothingToMove_marksInPlace`.
- [x] **Step 2: Implement** controller and job; service, notification and `AppGraph` wiring.
- [x] **Step 3:** `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`. Expected: BUILD SUCCESSFUL.
- [x] **Step 4: Commit** `feat(pipeline): Bin Files as a Binning job`.

**Done Nov 4** (`98908d9`): `BinFilesJobTest` 13, `MoveJobTest` 9. **Until Task 16 builds the UI, Bin Files is started over adb** (the same intent the Re-sort uses, `run_kind BinFiles`; see the handoff note). `ProjectMediaViewModel.onBinFiles` still shows its placeholder (B7 open). On the Analyze screen the status row says "Binning files" with "12 of 248" in the counts slot (no mockup; minimal text so the existing screen isn't wrong).

### Task 10: MediaStore after a move · phone: yes

**Files:** Create `move/MediaStoreNotifier.kt` (`MediaScannerConnection.scanFile(context, [dest, source], …)`), wire it in `AppGraph`. B11 is open: the scan itself is the recommendation; the checks below confirm what Gallery shows.

- [x] **Step 1:** Implement and wire (cloud). Commit `feat(move): tell MediaStore about moves`.
- [x] **Step 2 (phone):** Move one photo from `DCIM/Camera` with Bin Files. Check with `adb shell content query --uri content://media/external/images/media --projection _id:_data --where "_data LIKE '%<name>%'"` that one row points at the new path and none at the old, and whether the `_id` stayed the same (rename through FUSE). In Samsung Gallery: Pictures timeline position, the Camera album, the new category album, a Favourite set before the move, "Revert" on a photo edited before the move. In the Android photo picker and Samsung Gallery's picker: where it shows. Re-pick it into the same project ("already in this project") and another ("in another project's folder"). Record the answers in B11.

**Step 1 done Nov 4** (`6f38f63`): `MediaStoreNotifier`; scans are asynchronous and never fail a move.

### Task 11: Status, counts and Bin Files state · phone: no

**Files:** `projectmedia/MediaStatus.kt`, `ui/media/ProjectMediaViewModel.kt`. Test: `MediaStatusTest`, `MediaCountsTest`, `ProjectMediaViewModelTest`.

- `ActiveJob.Binning(done, total, paused)` and `ActiveJob.Saving(done, total, paused)` from the new phases (`activeJobOf`). Status lines: "Binning · 13 of 248 files", "Bin Files paused · 37 of 248 files", "Saving · moving 2 of 3 files".
- Counts by path (plan-2-notes): `readyToBin` = Sorted items plus Binned items not in place; `binned` = in place. `mediaCountsOf` takes an `inPlace` predicate.
- `binFilesBlockedReason`: while files move, "Bin Files is running"; the 2b reasons otherwise.
- The view model exposes the move's progress and the last report data for the (unbuilt) sheets, and refuses Save, Edit, Select actions and Re-sort with "Edits wait until the files have moved" while files move (2b state 6).

- [x] **Step 1: Tests** first (status lines, counts by path, refusals). **Step 2: Implement. Step 3: Commit** `feat(projectmedia): Bin Files status and counts`.

**Done Nov 4** (`98908d9` `activeJobOf`, `a512463`): `MediaStatusTest` +2, `MediaCountsTest` updated, `ProjectMediaViewModelTest.movingFiles_showsProgressAndHoldsEdits`. **Deviation (ruling):** counts stay by status, not by path: the engine sets Binned when a file reaches its target and Re-sort (Task 12) takes it back, so status and path agree; the planner still decides by path. Save, Remove, Retry and Set by hand are refused while files move.

### Task 12: Re-sort reaches binned items (B10) · phone: no (device test runs later)

**Files:** `db/SortDao.kt` (`RESORTABLE` includes `Binned`; unassigning sets `Analyzed`), `SortDaoTest` (device).

- [x] **Step 1: Device tests** (written, not yet run on the phone): `prepareResort_includesUnlockedBinnedItems`, `prepareResort_keepEdits_leavesLockedBinnedItems`.
- [x] **Step 2: Implement. Commit** `feat(sort): Re-sort reaches binned items`.

**Done Nov 4** (`831370b`). Device tests **written, not yet run on the phone**. `PipelineFakes.FakeSortStore.prepareResort` follows the same rule.

### Task 13: The removed-file record (Q2) · phone: no (device test runs later)

**Files:** `projectmedia/ProjectMediaRepository.kt` (`removeItems` adds `removed_file` rows for items whose `currentPath` is in a category folder, same transaction), `db/ProjectMediaDao.kt`, Leave them (`MoveDao.addRemoved(…, "left")`), registration drops a row when its path is added again; `BinFilesJob` drops rows whose file is gone.

- [x] **Step 1: Device tests** (written, not yet run on the phone): `remove_binnedItem_recordsItsPath`, `remove_unbinnedItem_recordsNothing`, `removedPath_isDroppedWhenAddedAgain`.
- [x] **Step 2: Implement. Commit** `feat(projectmedia): remember removed binned files`.

**Done Nov 4** (`011f23c`). Also records a removed item that sits inside the project folder without being binned (one a Re-sort took out of binned, B10), so the folder check never stops on it. Device tests `remove_binnedItem_recordsItsPath`, `remove_unbinnedItem_recordsNothing`, `remove_itemInsideTheProjectFolder_recordsItsPath`: **written, not yet run on the phone**. `removedPath_isDroppedWhenAddedAgain` is in `AppGraph.register` (no device test constructs `AppGraph`, 2b Task 6A); checked in Task 17 Step 7.

### Task 14: Save moves binned files · phone: yes (moves real files)

**Files:** `projectmedia/ProjectMediaRepository.kt` (`saveItem`, `moveItems`, `applyCategoryEdits` with binned items: the edit and a `Save` run plan in one transaction; then `PipelineController.saveMoves(project, runId)`), `ui/media/CategoryEditModel.kt` (remove the 2b "nothing is binned" note), `db/ProjectMediaDao.kt` (`category.folderName` set when a folder gets its first file, updated after a rename's last step).

- A category rename is one step per binned file into the new folder, then the empty old folder is removed (2b "Left for Plan 3"); a removed file keeps the old folder (Q2).
- The UI for state 6 exists in the 2b mockup; the progress card is shared with Bin Files (Task 16).

- [x] **Step 1:** Device tests: `saveRename_binnedItem_writesEditAndPlanTogether`, `renameCategory_movesEveryBinnedFileThenRemovesTheOldFolder`, `merge_movesTheDroppedCategorysFiles`, `rename_withARemovedFile_keepsTheOldFolder`.
- [x] **Step 2:** Implement; JVM tests for the plan; commit.

### Task 15: Media another project holds (P8) · phone: yes (copies real files)

**Files:** Create `media/ProjectFinder.kt` (a folder is a project folder when it holds `.binning/project.db`; reads its name read-only through `ProjectDatabaseProbe`). Modify `media/Registrar.kt` (a `Shared(path, project)` outcome instead of refusing; nothing registered yet), `media/MediaScanner.kt` exclusions, `AppGraph.addFolder` / `addFiles` (collect the shared files, ask, then copy and register), `ui/project/*` (the notice: Task 16 builds its UI).

- Copy each shared file with `MoveFiles.copyHashing` to `<project>/Copied from <other project name, made safe>/<original name>` (numbered if taken), check size and SHA-256 read back, then register the copy as a new item (`originalPath` = the copy). The other project's file is never moved.
- Cancel registers nothing of the shared files; the rest of the batch is registered as usual.

- [x] **Step 1: JVM tests:** `ProjectFinderTest` (`folderWithDatabase_isAProject`, `nestedInsideAProject_isFound`), `RegistrarTest` (`fileInAnUnlistedProjectFolder_isShared`, `fileHeldByAListedProject_isShared`, `sharedFiles_registerNothingUntilCopied`).
- [x] **Step 2: Implement** the logic (cloud), the copy step with its progress flow, and the notice UI once state 7 is approved.
- [x] **Step 3 (phone):** Add a folder that holds another project's files; Copy media files; `ls -lR` of both projects; SHA-256 of copies equals the originals; the other project's database unchanged.

### Task 16: Bin Files UI · phone: yes · blocked on mockup approval (B7, B8)

**Files:** Create `ui/media/BinSheets.kt` (confirm, report, unknown files), `ui/media/MoveCard.kt` (progress card with Pause/Resume and Stop, resume banner), `ui/project/SharedMediaNotice.kt` (P8). Modify `ui/media/ProjectMediaScreen.kt`, `ui/analyze/BottomBar.kt` (shows the move's state), `ui/project/ProjectSheet.kt` (adds disabled while files move).

- Match `2026-11-04-plan-3-binning.html` states 1–7 and 2b state 6 in dark and light (`screencap`).
- [x] **Step 1:** Owner approves the mockups and answers B7, B8.
- [x] **Step 2:** JVM model tests for each sheet model (like `DetailSheetModelTest`); build; phone screenshots.

### Task 17: End-to-end on the phone · phone: yes

- [x] **Step 1:** Fresh media (never Hawaii Photos or Maui): 20 photos and 3 clips pushed to `/sdcard/DCIM/avb-plan3/`, 2 photos to `/sdcard/Download/avb-plan3/`, a truncated JPEG (Failed), and one photo deleted after analysis (Missing). If an SD card is available, 2 photos on it (the copy path). New project "Plan3 E2E", add both folders, let analysis and sorting (LFM3-1.7B) finish.
- [x] **Step 2: Before.** `adb shell ls -lR` of the project folder and every source folder; `adb shell sha256sum` of every media file (save to `build/e2e/plan3/before.txt`).
- [x] **Step 3: Bin Files** (from the sheet once Task 16 is built; before that with the adb intent in the handoff note), and while it runs, `adb shell am force-stop com.agentic.binning` at about half way. Relaunch: the run resumes on its own (B3) and finishes. Check logcat for the step that was in flight.
- [x] **Step 4: After.** `ls -lR` and `sha256sum` again: every hash from Step 2 appears exactly once, at its item's `currentPath` (pull `project.db`: `select id,currentPath,status,contentHash from item; select * from move_step where runId = (select max(id) from move_run);`). The Failed and Missing items are where they were; no `.tmp` is left in `.binning/moving`; no source folder was removed.
- [x] **Step 5: Pause and Stop** on a second run (add 5 more photos, sort, Bin Files): Pause, wait, Resume; then Stop. The file in progress always finishes.
- [x] **Step 6: Save on a binned item:** rename one item, re-categorize one, rename a category: files move at once (2b state 6); `sha256sum` again.
- [x] **Step 7: Folder check:** `adb push` a stray JPEG into a category folder, Bin Files → the unknown-files sheet; Leave them; Bin Files works.
- [x] **Step 8:** Task 10's Gallery and picker checks on these files.
- [x] **Step 9:** Notes into `CLAUDE.md` and the B11 answers into the questions document. Commit.

---

## Handoff notes

- Built in the cloud on Nov 4 without a phone: see `docs/superpowers/notes/plan-3-cloud-handoff.md` for what is done, how it was verified, every ruling and the exact device tests to run.
- Never use Hawaii Photos or Maui as fixtures. Nothing in Plan 3 calls Claude.
