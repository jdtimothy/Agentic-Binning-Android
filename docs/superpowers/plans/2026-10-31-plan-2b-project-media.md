# Plan 2b: Project Media, Editing and Re-sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status: Tasks 1–6 done Nov 1, 2026; Task 6A code done Nov 2 (device tests and phone check pending, see Task 6A).** Drafted Oct 31 before Plan 2a reached GitHub. 2a merged into `main` as `24aa3bd` (PR #4) and was merged into `plan-2b-draft` on Nov 1. Task 6 checked every 2a dependency against the merged code; its findings (section "Task 6" below) replace the old **CONFIRM against 2a** markers, and Tasks 7–17 were updated to match. On Nov 1 the owner approved P1–P6 (P5 with its own wording) and Q1, Q4, Q6, Q10, Q12 and Q19's retry rule, and asked for **Task 6A** (open an existing project, remove a project from the app), planned below. Later on Nov 1 the owner settled every remaining question but Q3, including no Re-sort cost note (Task 4 dropped); on Nov 2 Q3 was settled too (option C, with a "Filters" button). PRs #5 and #6 are merged; Task 6A is on `plan-2b-task-6a`. Tasks 7–10 done Nov 2–3 (Task 9 is PR #12, Task 10 PR #13 on top of it). Tasks 11 and 12 merged (PRs #14, #15); Tasks 13 and 14 merged (PRs #16, #17), and the one-word category rule (PR #18). Tasks 16 and 17 done Nov 3 on `feat/plan-2b-task-16`. **Next: Task 15** (the owner set it aside on Nov 3; Task 17 started its Re-sort through the service instead). Task 6A's phone-check leftovers are listed under Task 6A. Before building a task, check "Product decisions" for answers it depends on.

**Goal:** The Project Media screen from the approved mockups: browse, search and filter a project's items; edit names and categories for one item or many; rename and merge categories; remove items; handle failed items; and re-sort the whole project with a chosen model. All edits change the database only, because nothing is binned until Plan 3.

**Architecture:** Same single module and hand-wired `AppGraph` as Plan 1. Pure rules (search query, name clashes, status line, move planning, layout check) are plain Kotlin with JVM tests. A `ProjectMediaRepository` turns Room rows into screen models and runs every edit in one transaction, refreshing the item's FTS row. Sorting jobs (Categorize now, Re-sort) go through 2a's single job arbiter, never a separate code path. The Compose screen follows the glass components from Plan 1 (`ui/glass/Glass.kt`, `GlassTokens`).

**Tech Stack:** As Plan 1 (Kotlin 2.2.0, AGP 8.13.0, Compose Material 3, Haze, Coil 3, Room 2.7 with the bundled SQLite driver).

**Spec and design:**
- `docs/superpowers/specs/2026-10-28-agentic-binning-design.md`, "Project Media screen", "3. Categorize", "4. Bin Files".
- Mockups: `docs/superpowers/mockups/2026-10-31-plan-2b-project-media.html` (open in a browser).
- Current projects mockup (Task 6A): `docs/superpowers/mockups/2026-11-01-plan-2b-current-projects.html`.
- Decisions: `docs/superpowers/mockups/2026-10-31-plan-2b-questions.md`. Every question is settled (see "Product decisions"); Q11 is deferred to Plan 3. If the user changes an answer, update the affected task before building it.
- Filter placement options for Q3: `docs/superpowers/mockups/2026-11-01-plan-2b-filters-options.html`.
- `docs/superpowers/plan-2-notes.md` and `CLAUDE.md`.
- Plan 2a: `docs/superpowers/plans/2026-10-30-plan-2a-sorting.md`, `docs/superpowers/notes/2026-11-01-plan-2a-phone-recheck.md` (the deferred Claude test and the Hawaii Photos rule, see "Handoff notes").

## Global Constraints

- Everything in Plan 1's Global Constraints still holds (device, versions, build command, one model loaded at a time, project folder layout).
- **Files are never moved, renamed or deleted in Plan 2b.** Edits change the database only. Save's file moves for binned items arrive with Plan 3's move engine; until then no item is binned, so Save never has files to move.
- **Remove from project deletes the item's database row (and its FTS and tag rows), never the file** (Q1, approved).
- **What the user set is locked** (Q5): a name or category the user sets, renames, merges or bulk-moves sets the item's `nameSetByUser` / `categorySetByUser`, or marks the category as the user's with `createdBy = "user"` (Task 6.1). Sorting must never change a locked value. Unlock clears the flag on Save.
- **Every edit waits for Save.** Each sheet has its own Save (Q6, approved); Cancel and closing drop the sheet's edits.
- **Schema migrations never destroy data** (plan-2-notes). Room schema JSON is committed. Any new column or table gets an `AutoMigration` and a `MigrationTestHelper` test.
- **Search input always goes through `FtsQuery.from`.** Never pass raw user text to `MATCH`.
- **One job at a time.** Categorize now and Re-sort run through `PipelineController`, which holds 2a's `ModelArbiter` (`JobKind.Sorting`). The arbiter only queues, so "busy" is decided from `PipelineController.state.phase.isRunning` (Task 6.3). Re-sort and Categorize now are **disabled, never queued**, while a run is in progress (analyzing, paused or categorizing), with the reason shown (Q12, approved Nov 1).
- **Category names go through `CategoryNames`**, which is 2a's `cleanName` and `SortValidator.normalize` (Task 6.5). Never add a second normalizer.
- **Device tests run by direct instrumentation**, never `connectedDebugAndroidTest` (see "Device tests").

## Review Focus

1. **A locked value survives every path.** Rename an item, bulk-move three items, rename a category; then run Categorize now and Re-sort with Keep my edits ticked. Nothing the user set changes. Test: Task 9 `resort_keepsLockedNamesAndCategories`.
2. **FTS stays in step with edits.** After renaming an item or a category, search finds the new name and not the old one (plan-2-notes: re-saving an analysis blanked `friendly_name` and `category`; 2a fixed that path, Task 6.6, but 2b edits need their own refresh). Test: Task 7 `rename_refreshesFtsRow`.
3. **Removing an item removes its FTS and tag rows** (plan-2-notes). Test: Task 7 `remove_deletesFtsAndTagRows`.
4. **User-typed search text never throws.** `AND`, `"`, `(`, `a:b`, `*`, emoji. Test: Task 1 (JVM) and Task 7 `search_withFtsSyntax_doesNotThrow` (device, real FTS5).
5. **Merging and renaming never produce two categories with the same normalized name.** Test: Task 2 and Task 7 `merge_movesItemsAndDropsTheOther`.
6. **Re-sort never runs while the vision model is loaded.** Test: Task 9 `resort_isRefusedWhileARunIsInProgress`.

## Device tests

`connectedDebugAndroidTest` uninstalls the app afterwards, which deletes the downloaded models (`files/geniex/models`). Never use it. Build both APKs, install them, and run one class at a time by direct instrumentation (from Git Bash on the PC, with `CLAUDE.md`'s `JAVA_HOME`):

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
adb shell am instrument -w -e class com.agentic.binning.projectmedia.ProjectMediaRepositoryTest com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner
```

Add `#methodName` to the class for a single test. Read results from the instrument output and logcat, not the screen. Turn on Do Not Disturb before scripting taps. Report a device check as passed only when it ran on an identified device (`adb devices -l`); compiling `assembleDebugAndroidTest` is not running it.

## Product decisions

### Approved Nov 1, 2026 (from Task 6)

The owner approved the recommended answer of each, with P5's wording replaced by their own (and P5 later superseded by Q13). Tasks below are written to these answers.

- **P1. A Re-sort that fails or is stopped midway** (Task 9). Before clearing anything, Re-sort checks that the chosen backend can run (Claude: a key is saved; LFM3-1.7B: downloaded). Only then does it clear the unlocked assignments and sort. A network failure, rate limit or Stop after that point leaves the cleared items waiting (they block Bin Files until Categorize now succeeds), and the sheet says so before the user starts.
- **P2. Unlock keeps the item's category** (Task 7, Q5). Automatic sorting only touches items with no category, so an unlocked item stays where it is until a Re-sort. The detail sheet says "Sorting may change this on the next Re-sort" next to the Unlock link.
- **P3. The same-name check is 2a's normalizer** (Task 6.5). It treats `Mama's` / `Mamas`, `Sea Lions` / `Sealions` and plurals before the last word as different names (and keys `Bus` as `bu`, harmlessly). Revisit only with real duplicates, since a stricter check changes 2a's sorting too.
- **P4. Category names are capped at 40 characters**, the sorter's limit (the draft allowed 60), so a folder name never depends on who named the category.
- **P5. The Re-sort cost note** (Task 15, Q13). **Superseded the same day: the owner chose no cost note at all (Q13), so `ResortCost` was deleted (Task 4 dropped).** For the record: `ResortCost` counted the prompt and the reply JSON only; Claude's adaptive thinking shares the 16,000-token cap per call, and a chunk can take up to 6 calls (`SortingCategorizer.MAX_CALLS_PER_CHUNK`). The note shows the figure with exactly this sentence: **"Estimated base cost; thinking and retries may increase it."** (owner's wording; not "at least"). Calibrate against real usage once the deferred Claude test runs (`TextReply` has `inputTokens` / `outputTokens`; `sort_run` doesn't store them yet).
- **P6. Retry analysis lifts a remembered Stop and starts analysis** (Task 7, Q19), like `AnalysisLauncher.userAdd`. Categorize now leaves a Stop alone.

### Approved Nov 1, 2026 (project list design, owner's own)

- **P7. Current projects.** The project sheet's list is headed **Current projects** (the projects loaded into the app). Under the heading sit two buttons, **Open project** and **New project**. An **Edit** button at the right of the heading puts a small **×** on each project; tapping × takes the project off the list and never deletes its database or files. No long explanations in the UI. Mockup: `docs/superpowers/mockups/2026-11-01-plan-2b-current-projects.html`. (The owner said "create new project"; the mockup labels it "New project", matching 2a's page title and fitting beside "Open project".)

### Approved Nov 1, 2026 (second round)

- **P7a. Keep 2a's "Current project" label** on the open project's block above the list (the owner chose this over the recommended "Now open").
- **P7b. Undo after ×:** a "Removed <name>" toast with Undo for about 5 seconds; Undo puts the row back where it was and reopens it if it was open.
- **P7c. One confirmation only:** × on a project with analysis or sorting running asks "Stop analysis and remove <name>?" (Cancel / Remove); otherwise × acts at once.
- **P7d. No projects:** the sheet shows the Current projects heading, Open project, New project and "No projects yet" (changes 2a's `startPage`).
- **P8. Declined:** registration keeps 2a's rule and skips only listed projects' folders. A removed project's folder is not skipped. Nothing leaks in 2b; revisit before Plan 3 bins anything (listed under "Left for Plan 3").
- **P9. Back up first:** opening an older-schema project copies `project.db` to `.binning/project.db.v<N>.bak` before Room upgrades it.
- **Project list safety:** Task 6A makes every write to `projects.json` (create, switch, remove, restore) refuse when the file exists but can't be read, instead of overwriting it with an empty list.
- **Merge plan:** merge PR #5 after the owner's local review; build Task 6A on a fresh branch with its own PR.
- **Housekeeping:** `ResortCost` deleted (Q13), `gradlew` made executable, `CLAUDE.md` "Current state" updated.

### Approved (questions document)

Q1 (Remove from project never deletes media), Q2, Q4 (Re-sort keeps edits by default), Q5, Q6 (Save and Cancel are scoped per sheet), Q7, Q8 (Re-sort and Edit on the categories header, Select on the result line, as mocked), Q9 (Waiting and Failed as chips), Q10 (merge two categories at a time; the user chooses the surviving name), Q12 (sorting actions are disabled during analysis, not queued; reason "Re-sort is available when analysis is stopped or done"), Q13 (**no cost note**), Q14 (the Re-sort picker lists exactly what Settings lists), Q15 (search as mocked), Q16 (tile markers as mocked, no locks on tiles), Q17 (oldest first), Q18 (two facts at most in the status line), Q19 (an explicit Retry resets attempts; a failed item can be set by hand with both locks). **Q11 is deferred to Plan 3** (nothing is binned in 2b).

### Approved Nov 2, 2026

- **Q3. Filters: option C, with the button labelled "Filters".** An All / Photos / Videos toggle is always visible ("videos only" is one tap); a **Filters** button beside it (the mockup's "More", renamed by the owner) opens a sheet for shot type and date; set ones show as blue chips with × beside the toggle. Mockup: `docs/superpowers/mockups/2026-11-01-plan-2b-filters-options.html`, option C.

### Open

None.

---

## File Structure

All under `src/main/java/com/agentic/binning/` unless noted. Tests mirror it under `src/test/java/...` (JVM) and `src/androidTest/java/...` (device).

| File | Responsibility | State |
| --- | --- | --- |
| `search/FtsQuery.kt` | User text → safe FTS5 MATCH expression | Done (Task 1) |
| `projectmedia/CategoryNames.kt` | Clean names; normalized clash check, both through 2a's `cleanName` / `SortValidator.normalize` | Done (Task 2; changed in Task 6) |
| `projectmedia/MediaStatus.kt` | Status line text, tone, Categorize now, Bin Files enabled | Done (Task 3) |
| `move/MovePlanner.kt` | Target paths with " 2" suffixes, never overwriting (for Plan 3) | Done (Task 5) |
| `move/LayoutCheck.kt` | Count check after moves; ignores `.binning/` and removed files (for Plan 3) | Done (Task 5) |
| `project/ProjectDatabaseProbe.kt`, `project/ProjectOpenRules.kt` | Read-only check of an existing project's database; open/refuse decisions | Task 6A |
| `project/ProjectRegistry.kt`, `ui/project/*` (modify) | Canonical-path matching, `remove` / `restore`; the Current projects section (Open project, New project, Edit with ×) | Task 6A |
| `db/ProjectMediaDao.kt` | Grid rows, counts, filter options, edit queries | Task 7 |
| `projectmedia/ProjectMediaRepository.kt` | Screen models; every edit in one transaction with FTS refresh | Task 7 |
| `projectmedia/MediaFilter.kt` | Category / Waiting / Failed chip, kind, shot type, date; applied in SQL | Task 7 |
| `projectmedia/ResortRequest.kt` | Re-sort options (model, keep edits) handed to 2a's sorter | Task 9 |
| `ui/media/ProjectMediaViewModel.kt` | Screen state, sheets stack, select mode, Save | Task 11 |
| `ui/media/ProjectMediaScreen.kt` | Header, search, chips, filters, result line, grid, dock, empty state | Task 11 |
| `ui/media/MediaTile.kt` | Tile with badges (video duration, binned, waiting, failed, ticked) | Task 11 |
| `ui/media/DetailSheet.kt` | Media, name, category, lock markers, description, actions, tags, details | Task 12 |
| `ui/media/CategoryPickerSheet.kt` | Category list with New category… (detail and bulk move) | Task 12 |
| `ui/media/SelectionBar.kt`, `ui/media/RemoveDialog.kt` | Bulk actions and the remove confirmation | Task 13 |
| `ui/media/CategoryEditSheet.kt` | Rename and merge | Task 14 |
| `ui/media/ResortSheet.kt` | Model picker, Keep my edits, changes / doesn't change | Task 15 |
| `ui/media/FailedSheet.kt` | Retry, set by hand, remove | Task 16 |
| `ui/media/MediaIcons.kt` | Icons not already in `AnalyzeIcons` / `ProjectIcons` | Task 11 |

---

## Done on this branch (Tasks 1–5)

Written and unit-tested on Oct 31, 2026, before 2a, in a JVM-only scratch build (the cloud session then had no Android SDK). On Nov 1 they ran inside the real Android build for the first time, on the merge of `main` @ `24aa3bd` into this branch: `./gradlew test --rerun-tasks` passed 449 tests per variant (debug and release, 40 classes, 0 failures/errors/skips), including all 46 tests of these six classes. `assembleDebug assembleDebugAndroidTest` succeeded. Task 6 then changed Task 2 and added one Task 4 test (below); see "Task 6" for the final counts.

### Task 1: FTS5 search query ✅

- `FtsQuery.from(input): String?`. Null for blank or punctuation-only input (show all items). Each word becomes a quoted phrase, with `"` doubled. Words are ANDed. Only the last word gets a prefix `*`, and not when the input ends with a space. At most 12 words.
- Why only the last word: with `porter`, `"resting"*` also matches "restaurant". Checked against SQLite FTS5 with `tokenize='porter unicode61'`.
- Test: `search/FtsQueryTest.kt` (8 tests).

### Task 2: Category name clashes ✅

- `CategoryNames.clean(name)` = 2a's `cleanName(name, SortValidator.MAX_CATEGORY_NAME)`: folder-illegal characters become spaces, spaces collapse, capped at 40 characters, no trailing dot or space.
- `CategoryNames.key(name)` = 2a's `SortValidator.normalize(name)`.
- `CategoryNames.clash(name, existing, except)`: returns the name it clashes with.
- **Settled in Task 6 (commit `b55ef7e`):** the draft's own key and its 60-character cap were replaced by 2a's functions, so sorting and editing agree by construction. The draft's stricter matches no longer clash (Task 6.5; P3, approved).
- Test: `projectmedia/CategoryNamesTest.kt` (11 tests; was 7).

### Task 3: Status line and Bin Files state ✅

- `mediaStatus(MediaCounts, ActiveJob?): MediaStatus`.
  - Text examples: "34 files ready to bin · 3 waiting to be categorized", "All files binned · 1 failed", "Analyzing 12 left", "Categorizing…", "Saving · moving 2 of 3 files".
  - Tone; the Categorize now action; whether Bin Files is enabled, and its count.
- Bin Files is enabled when files are ready, nothing is pending, analyzing or waiting, and no job runs. Failed and missing items never block it (Q7, Q18).
- Test: `projectmedia/MediaStatusTest.kt` (9 tests).

### Task 4: Re-sort cost estimate (dropped)

Written Oct 31 (`ResortCost` and 6 tests). Dropped Nov 1: the owner chose no Re-sort cost note (Q13), so the code and tests were deleted in `bb4bbce`.

### Task 5: Move planner and layout check (for Plan 3) ✅

These belong to Plan 3's move engine. They are done early because they are pure logic.

- `MovePlanner.plan(projectDir, requests, existingPaths)`:
  - Destinations are `<project>/<folder>/<name><ext>`, with folder and file names made safe through `ProjectFolders.folderNameFor`.
  - Never overwrites: a name taken on disk or earlier in the plan gets " 2", " 3" and so on.
  - Names compare ignoring case.
  - A file already in place keeps its name, including an earlier " 2" suffix.
  - Results come in item id order.
- `LayoutCheck.check(projectDir, categoryFolders, binnedPaths, filesOnDisk, ignored)`:
  - Returns the missing and unexpected files.
  - Ignores `.binning/`, anything outside the category folders, and `ignored` paths: files of items removed after binning (Q2).
- Tests: `move/MovePlannerTest.kt` (11), `move/LayoutCheckTest.kt` (6).

---

## Wiring (after 2a is merged)

### Task 6: Read 2a and settle every CONFIRM ✅

Done Nov 1, 2026 against `main` @ `24aa3bd` (Plan 2a, PR #4, merged into `plan-2b-draft`). Every fact below was read from the merged code and its tests; anything 2b still has to add is under "Not in 2a yet", never described as existing.

#### 6.1 Schema, statuses and locks

- `PROJECT_DB_VERSION = 3` (`db/ProjectDatabase.kt`); schema JSON for 1–3 is in `schemas/`. v2 added `item.attempts` and `project_meta.stoppedByUser`; v3 added `sort_run`. Both are `AutoMigration`s tested in `ProjectDatabaseMigrationTest` (device).
- `ItemStatus` (`db/Entities.kt`): `Pending, Analyzing, Analyzed, Failed, Sorted, Binned, Missing`.
  - `AnalysisDao.saveAnalysis` sets `Analyzed` when `categoryId` is null, else keeps `Sorted`; `SortDao.applyChunk` sets `Sorted`.
  - `AnalysisEngine` writes `Missing` for a file that is gone. Nothing writes `Binned` before Plan 3.
- `item` columns 2b uses: `categoryId`, `friendlyName`, `nameSetByUser`, `categorySetByUser`, `failureReason`, `attempts`, `takenAt`, `kind`, `durationMs`. Shot type, suggested name, description and actions are on `analysis` (`AnalysisEntity`); tags on `tag` / `item_tag`.
- `category`: `id, name, description, createdBy, folderName`. No unique index on `name`. `applyChunk` writes `createdBy = "model"` and `folderName = null`; nothing in 2a writes any other `createdBy`. There is **no separate "renamed by the user" column**.
- Lock semantics enforced by 2a today:
  - `SortDao.uncategorized()` / `observeUncategorizedCount()` skip items with `categorySetByUser = 1`.
  - `applyChunk` assigns only items with `categoryId IS NULL AND categorySetByUser = 0` (`isAssignable`, guarded again in `assignItem`), and keeps `friendlyName` when `nameSetByUser` (SQL `CASE`).
  - No 2a code renames, merges or deletes a category.
  - `SortPrompt.categoryLine` shows a category as `locked` when `createdBy != "model"`, and the system prompt says a locked category "can receive items, nothing else".
- **Decision (no schema change for locks):** 2b marks a category the user created, renamed or kept in a merge with `createdBy = "user"`. That is already what 2a's prompt reads as locked. Re-sort without Keep my edits sets it back to `"model"`. A schema change is needed only for Plan 3's `removed_file` table; 2b's first schema change, if any, is v4 with `AutoMigration(3, 4)` and a `ProjectDatabaseMigrationTest` case.

#### 6.2 Categorizer and controller entry points, model selection

- `pipeline/Categorizer.kt`: `interface Categorizer { suspend fun categorize(project, onProgress): CategorizeOutcome }`, outcomes `NothingToDo`, `Done(sorted, newCategories)`, `Partial(sorted, left, message)`, `Unavailable(reason: SortUnavailable)`.
- `SortingCategorizer(store: SortStore, modelFor: (SortingModelChoice) -> TextModel, choice: suspend () -> SortingModelChoice, ...)`. It always sorts `store.uncategorized()`: there is no "sort all items" mode and no per-call model override. `AppGraph` builds it with `choice = { settings.settings.first().sortingModel }` and `modelFor = ::textModelFor`.
- `SortingModelChoice { Claude, Lfm3_1_7B }` (`settings/SettingsStore.kt`). Settings lists exactly these two (`SortingOptions` and `pickSorting` in `ui/settings/SettingsModel.kt`); Qwen was dropped in 2a.
- Claude: `ClaudeTextModel.MODEL = "claude-sonnet-5-5"`, `MAX_TOKENS = 16_000` per call with adaptive thinking sharing that cap (effort MEDIUM), no SDK retries. 2a has no price catalog. (Task 6 first kept the Re-sort price in `ResortCost` with a guard test; both were deleted when the cost note was dropped, Q13.)
- **Categorize now already exists:** `PipelineController.categorizeNow(project)`, started through `AppGraph.startAnalysis(ref, RunKind.CategorizeOnly)` so it runs in `AnalysisService`. It is a no-op while a run for the same project is active (that run categorizes at its end anyway). `AppGraph.resumeAnalysis(ref)` (Resume) lifts a Stop and analyzes or categorizes; Categorize now should call `startAnalysis(ref, RunKind.CategorizeOnly)` directly, which does not lift a Stop.

#### 6.3 Arbiter: ownership, busy, cancellation, release

- `pipeline/ModelArbiter.kt`: `enum class JobKind { Analysis, Sorting, Binning }`; `suspend fun <T> withJob(kind, block): T`; `val active: StateFlow<JobKind?>`. One instance, `AppGraph.modelArbiter`, shared with `PipelineController`.
- **It never refuses.** `withJob` waits on a fair `Mutex` until earlier jobs finish. There is no try/refuse call.
- The controller holds it per analysis batch (including while Paused, which waits inside the batch) and around `categorize`. Between the last batch and categorizing it is briefly free while the run is still going, so `arbiter.active == null` does **not** mean "no analysis". The reliable signal is `PipelineController.state.value.phase.isRunning`.
- Cancellation: `PipelineController.stop()` cancels every run job and `model.cancel()`s the vision model; `stopAndReset()` (project switch) waits for them. `SortingCategorizer` records a cancelled run as `partial` / `Stopped`, rethrows, and always `release()`s the text model in `finally` (`NonCancellable`). The vision model is released before sorting loads.

#### 6.4 "Waiting to be categorized"

Exactly 2a's uncategorized set: `status = 'Analyzed' AND categoryId IS NULL AND categorySetByUser = 0` (`SortDao.observeUncategorizedCount`, `uncategorizedRows`). It is not a status of its own. An item with `categorySetByUser = 1` and no `categoryId` is in no bucket, so 2b must never write that state (see Task 7's unlock rule).

#### 6.5 Category names: one normalizer

- 2a: `SortValidator.normalize` (lower case, diacritics and punctuation removed, `the/a/an` dropped, last word singular) and `sorting/Names.kt` `cleanName(name, max)` with `SortValidator.MAX_CATEGORY_NAME = 40` (illegal path characters become spaces).
- **Resolved:** `CategoryNames` now delegates: `key = SortValidator.normalize`, `clean = cleanName(_, MAX_CATEGORY_NAME)`, `MAX_LENGTH = 40`. Its own singular rules are gone; `clash()` stays as the edit-side API. Sorting and editing now agree by construction. 2a's sorting behaviour is unchanged.
- Cases where the draft's own key was stricter and 2a's is not (now pinned in `CategoryNamesTest`): `Sea Lion` vs `SEALIONS`, `Mama's` vs `Mamas`, `Sea Lions Beach` vs `Sea Lion Beach` (only the last word is made singular). Kept by P3 (approved Nov 1).

#### 6.6 Search and FTS

- `AnalysisDao.search(ftsQuery)` passes its argument straight to `MATCH`. 2a has **no sanitizer and no production caller** (only device tests, which use column filters such as `category:beach`). `FtsQuery.from` is therefore the only sanitizer, kept as is; every 2b caller passes its output, and a null result means "no search".
- The plan-2-notes FTS bug is **fixed in 2a**: `FTS_REBUILD_ROW` (`db/SortDao.kt`) rebuilds a row from the tables and is used by both `saveAnalysis` and `applyChunk`, so a re-analysis keeps the friendly name and category and a sort keeps the description and tags.
- Gaps 2b must close in Task 7 (not done here):
  - `FTS_REBUILD_ROW` inner-joins `analysis`, so an item with no analysis (a Failed item set by hand, Q19) gets no FTS row. Task 7 needs a rebuild that left-joins `analysis`.
  - 2a has no item delete. `item_fts` is not foreign-keyed, so `removeItems` must delete the FTS row itself (`analysis` and `item_tag` cascade).
  - A category rename or merge must rebuild the FTS row of every item in the affected categories.

#### 6.7 Settings and API key

- `SettingsStore.settings: Flow<AppSettings>` with `sortingModel` (key `sorting_model`), `sortingModelChosenByUser` (`sorting_model_chosen_by_user`); `setSortingModel(choice)`.
- `ApiKeys` (`AppGraph.apiKeys`): `info: Flow<ApiKeyInfo?>` (non-null means a key is saved; only `last4` is exposed), `save`, `load`, `clear`. Stored encrypted under `claude_api_key_enc`, Keystore alias `claude_api_key`. The Re-sort sheet only needs `info` to know whether Claude can run, and `ModelDownloader.isReady(spec)` / `pickSorting` for LFM3-1.7B.

#### 6.8 Not in 2a yet: what Re-sort requires

These are additions Task 9 must make; none exists today.

1. **A model override per run.** `SortingCategorizer` reads `choice()` once per `categorize`. Add an optional `choice: SortingModelChoice?` to `Categorizer.categorize` (null = Settings), or a request object.
2. **A clearing step**, one `SortDao` transaction (`prepareResort(keepEdits)`):
   - unassign every `Sorted` item with `categorySetByUser = 0` (`categoryId = NULL`, `status = 'Analyzed'`); the friendly name is replaced on reassignment unless `nameSetByUser`;
   - delete `createdBy = 'model'` categories left with no items;
   - with `keepEdits = false`, first clear `nameSetByUser` / `categorySetByUser` and set every `createdBy` to `"model"`;
   - rebuild the FTS row of every changed item.
3. **A controller entry with refusal.** `launchRun` silently absorbs or ignores a start while a run is active. Add `PipelineController.resort(project, request): ResortStart` (`Started` / `Refused(reason)`), refused while `state.phase.isRunning`, and a `RunKind.Resort` through `AnalysisService`, so Stop, project switches and the Categorizing phase apply as for any run.
4. Failed and Missing items are left out (they are never `Analyzed`), as are Binned items until Plan 3 decides.

#### 6.9 Verification (Nov 1, 2026, cloud container, no phone)

- Environment: OpenJDK 21.0.12, Android SDK at `/root/android-sdk` (platform 35, build-tools 35.0.0) through the gitignored `local.properties`. `gradlew` was committed without the executable bit, so it was run as `bash ./gradlew` (fixed later, `ea5a67c`).
- `main` @ `24aa3bd`, `./gradlew test --rerun-tasks`: 403 tests per variant, 0 failures. Four earlier attempts died on Maven Central HTTP 429 while downloading dependencies, before any test ran; nothing in the build was changed to get past them.
- Merge commit `53c28b0`: `./gradlew test --rerun-tasks` 449 per variant, 0 failures; `assembleDebug assembleDebugAndroidTest` succeeded.
- After this task's changes: `./gradlew test --rerun-tasks` 454 tests per variant (debug and release, 40 classes, 0 failures/errors/skips); `assembleDebug assembleDebugAndroidTest` succeeded. The six 2b classes then held 51 tests (`FtsQueryTest` 8, `CategoryNamesTest` 11, `MediaStatusTest` 9, `ResortCostTest` 6, `MovePlannerTest` 11, `LayoutCheckTest` 6); with `ProjectFoldersTest` (3), 54, the successor of the old "49". 2a's `SortValidatorTest` (46) is unchanged.
- After `ResortCost` was dropped (`bb4bbce`) and `gradlew` made executable (`ea5a67c`): `./gradlew test --rerun-tasks` 448 tests per variant (39 classes, 0 failures/errors/skips); `assembleDebug assembleDebugAndroidTest` succeeded. The five remaining 2b classes hold 45 tests; 48 with `ProjectFoldersTest`.
- **Not run:** any instrumented test or phone check (no device was attached). The authenticated Claude request remains deferred with the owner's approval (`notes/2026-11-01-plan-2a-phone-recheck.md`); no key was requested and no paid call made.

- [x] **Step 1:** Read 2a and record the facts above.
- [x] **Step 2:** `./gradlew test --rerun-tasks` with 2a merged; Tasks 1–5 pass.
- [x] **Step 3:** Commit the updated plan.

### Task 6A: Open an existing project, and remove a project from the app

Requested by the owner on Nov 1, 2026. **Code done Nov 2 on `plan-2b-task-6a` (Steps 1–4); device tests and the phone check (Step 5) are pending on the PC.**

**Built (Nov 2):**
- `project/ProjectRegistry.kt`: canonical-path matching (`find`, `samePath`), strict writes (`ProjectListUnreadableException` from `create`, `setCurrent`, `clearCurrent`, `remove`, `restore`), `remove` returning `RemovedProject`, `restore`.
- `project/ProjectOpenRules.kt` (pure): placement, the decision table, backup naming, listed name, next project after removal, the short refusal lines. `db/ProjectSchema.kt`: Room identity hashes for schemas 1–3, checked against `schemas/` by `ProjectSchemaTest`.
- `project/ProjectDatabaseProbe.kt`: the read-only probe (bundled driver, `SQLITE_OPEN_READONLY`).
- `AppGraph`: `openExistingProject`, `removeProject`, `restoreProject` (Undo), `isRunning`; `openLocked` gains `existingOnly`, lists the project only after its database opened, and matches the open project by canonical path.
- Sheet: Current projects (`currentProjectsUi` in `ProjectSheetModel.kt`), Open project / New project buttons, Edit/Done with ×, the "Stop analysis and remove …?" dialog, the Undo toast, `startPage` always Main.

**Deviations from the spec below** (for the owner's review):
- Rows other than the open project show no item counts (the mockup did): counts would mean opening every project's database each time the sheet opens. The open row shows its counts; a missing folder shows "Folder missing".
- The device tests never construct `AppGraph`: instrumented tests run in the app's own process, so it would read and write the real `projects.json` and could remove real projects. Removing the active project while it runs, opening the next project, Undo reopening, and the Open project picker are checked in the phone check instead; the device tests cover the probe, reopening, the backup, the `/sdcard` spelling and file preservation.
- No Compose previews: the app has none set up. Step 4 compares with the mockup on the phone with `screencap`.
- The Undo toast sits at the bottom of the sheet (a sheet is its own window), not over the screen.

**Where it fits:** first, before Task 7, on a fresh branch after PR #5 is merged (approved merge plan). It doesn't depend on Project Media (Tasks 7–17), and it changes the open and switch path in `AppGraph` that Task 9's Re-sort builds on, so doing it first means Task 9 is written against the final open/close rules. It also gives a way back to a project after a reinstall or a cleared app data, which a 2b tester will need before the end-to-end check (Task 17). It touches the registry, `AppGraph`'s open path, registration's scan exclusions and the project sheet; nothing in `projectmedia/`, the sorter or the model backends.

**Goal:**
- **Open project** (button under Current projects): pick a folder that already holds an Agentic Binning project, check its database without changing it, and open it with its saved analyses, categories, locks, sort runs and Stop flag as they are, never recreating them, and without a second registry entry.
- **Remove from Current projects** (Edit, then ×): forget the project's registry entry and nothing else. Its media, folders, `.binning/project.db` (and journal) and thumbnails stay exactly as they are, whether or not the folder still exists.

**Files:**
- Create: `project/ProjectDatabaseProbe.kt` (read-only check of a candidate `project.db`), `project/ProjectOpenRules.kt` (pure decisions from the probe's facts and the registry).
- Modify: `project/ProjectRegistry.kt` (canonical-path matching, `remove`, strict reads for removal), `AppGraph.kt` (`openExistingProject`, `removeProject`, scan exclusions), `ui/project/ProjectSheet.kt`, `ui/project/ProjectSheetHost.kt`, `ui/project/ProjectSheetModel.kt`, `ui/project/ProjectIcons.kt`.
- Test: `src/test/java/com/agentic/binning/project/ProjectRegistryTest.kt` (extend), `src/test/java/com/agentic/binning/project/ProjectOpenRulesTest.kt`, `src/test/java/com/agentic/binning/ui/project/ProjectSheetModelTest.kt` (extend), `src/androidTest/java/com/agentic/binning/project/ProjectOpenRemoveTest.kt`.
- No schema change: everything needed is already in `project_meta` and Room's own `room_master_table`.

#### What 2a does today (verified Nov 1)

- The registry is `files/projects.json` in app-private storage: `{"projects":[{name,path}],"current":"<path>"}` (`ProjectRegistry`). Entries match by **exact path string**. A file that can't be parsed reads back as an empty registry, so the next write would drop every entry.
- `AppGraph.openProject(ref)` checks only that the folder exists (`requireProjectFolder`), then `openProjectDatabase` **creates** `.binning/project.db` if it is missing and inserts a `project_meta` row if there is none. Room runs the 1→2→3 AutoMigrations on an older file. A newer file (a higher `user_version`) makes Room throw on first access; nothing catches that.
- Switching stops the pipeline (`pipelineTarget = null`, `pipeline.stopAndReset()`, `deferredStart = null`), opens the new database, `registry.setCurrent`, then closes the old one. All of it runs under `openLock`.
- `restoreCurrentProject()` clears `current` (keeping the entry) when the folder is missing. With no current project the sheet opens on the New project page (`startPage`).
- Registration (`scanExclusions`) skips every other registered project's folder and the current project's `.binning`, and `MediaScanner` skips every dot-folder. Cross-project duplicates are found by opening every registered project's database (`openOtherProjects`).
- There is no way to add an existing folder to the registry, and no way to remove an entry.

#### Open project

1. **Pick.** The system folder picker (`OpenDocumentTree`), starting in `Agentic Binning/` when possible. The tree URI becomes a path with `MediaUris.treeUriToDir`; a URI with no path is refused ("Pick a folder on this phone or its SD card"). If the user picks the `.binning` folder itself, use its parent.
2. **Match the registry by canonical path** (`File.canonicalPath`, also applied to stored entries when comparing). If the folder is already registered, open that entry: nothing is added, and the entry keeps its name. If the folder lies inside a registered project, or contains one, refuse: "This folder is inside <name>" (or "This folder contains <name>"). Nested projects would confuse scan exclusions and, later, binning.
3. **Probe, read-only** (`ProjectDatabaseProbe`). Open `.binning/project.db` with the bundled driver in read-only mode, never through Room, and read `PRAGMA user_version`, the identity hash in `room_master_table`, the `project_meta` row and `PRAGMA quick_check`. Then close it. The probe never writes, so it never creates a missing file, never rolls back a journal and never migrates.
4. **Decide** (`ProjectOpenRules`), from the probe's facts:

   | Database | Result | Changes on disk |
   | --- | --- | --- |
   | `.binning/project.db` missing (folder exists) | Refused: "No project in this folder" | None. No `.binning` is created. |
   | Not SQLite, unreadable, `quick_check` fails, no `room_master_table` or no `project_meta` row | Refused: "This project can't be opened" | None |
   | `user_version` > `PROJECT_DB_VERSION` | Refused: "Made with a newer app version. Update to open it." | None. Room is never opened on it; never add a destructive or downgrade fallback. |
   | `user_version` = `PROJECT_DB_VERSION` but the identity hash differs from the build's | Refused: "This project can't be opened" | None |
   | `user_version` < `PROJECT_DB_VERSION` (1 or 2) | Opened after a backup (P9, approved): copy to `.binning/project.db.v<N>.bak`, then let Room's AutoMigrations upgrade it | The backup, then the migration |
   | Current version, hash matches, meta present | Opened | None by the open itself |

   A hot `project.db-journal` beside the file (an interrupted write) makes the read-only probe fail. Report it as "This project can't be opened" and change nothing; SQLite's normal recovery on a read-write open is a later decision, not part of this task.
   Every refusal is one short line under the two buttons (mockup state 6), in the sheet's existing warning style; nothing else explains it.
5. **Open.** Through the same path as a switch (`openLock`, stop and reset the pipeline, close the old database), but with a variant of `openLocked` that **requires** the database to exist and never inserts `project_meta`. The registry entry is added only after the database opened, named from `project_meta.name` (falling back to the folder name). `stoppedByUser` is honoured: a project the user stopped doesn't start analysing on its own (`resumeAnalysisIfPending` / `AnalysisLauncher.autoStart` already do this).
6. **Opening is idempotent.** Opening the same folder again, through another path spelling (`/sdcard/...` vs `/storage/emulated/0/...`, a trailing slash) or after a restart, never adds a second entry.

#### Remove from Current projects (Edit, ×)

1. **Scope:** only the registry entry. No file, folder, database, journal, backup or thumbnail is deleted, moved or rewritten. The implementation calls no `delete`, `deleteRecursively`, `renameTo` or `Files.move` on anything under the project folder; `ProjectRegistry.remove` writes only `projects.json`.
2. **The list must be readable** (approved). Every write to `projects.json` (`create`, `setCurrent`, `remove`, `restore`) reads strictly first: if the file exists but can't be parsed, the write is refused ("Couldn't update the project list") and the file is left as it is, instead of 2a's behaviour of reading it as empty and writing an empty list over it. A missing file still means "no projects".
3. **No confirmation, with Undo.** × removes at once and shows "Removed <name>" with Undo (P7b, approved). Undo puts the entry back at its old position (`ProjectRegistry.restore(ref, index)`) and, if it was the open project, reopens it. The one exception: × on a project with a run in progress asks "Stop analysis and remove <name>?" (Cancel / Remove) first (P7c, approved).
4. **Active work.** Edit (and so ×) is disabled while media is being added or a project is being created (the sheet's existing `registration` and `creating` states), since those write to the open database. A run in progress (analysing, paused, categorizing, later Re-sort) is stopped the way a switch stops it: under `openLock`, `pipelineTarget = null`, `pipeline.stopAndReset()` (which waits for the models to be released), `deferredStart = null`, then the database is closed and `current` cleared. `stoppedByUser` is not written: the project's own database keeps whatever it had.
5. **Missing folder.** Remove works when the folder no longer exists, and never recreates it (no `requireProjectFolder`, no database open). Such rows show "Folder missing" in place of their counts.
6. **Afterwards.** If the removed project was the current one, open the first remaining project (in list order) whose folder exists and whose database passes the probe. If none does, the sheet shows the empty Current projects section with Open project and New project (P7d, approved; today 2a shows the New project page). Removing another project leaves the current one open.
7. **Scans after removal:** unchanged from 2a (P8 declined). A removed project's folder is no longer skipped; in 2b it holds only `.binning/`, which the scanner skips anyway.

#### Where the actions appear (P7 and P7a–P7d, approved)

Mockup: `docs/superpowers/mockups/2026-11-01-plan-2b-current-projects.html` (states 1–6, dark and light, live).

- **The project sheet's main page** (2a's `ProjectSheet.kt`):
  - Top: the open project's block, unchanged from 2a: the **Current project** label (P7a), counts, Add a folder and Pick photos and clips.
  - **Current projects** heading, with **Edit** at its right (hidden while the list is empty).
  - Under the heading, two buttons side by side: **Open project** (system folder picker) and **New project** (2a's New project page). They replace 2a's single "New project" chip.
  - The list: every project in the app, the open one first with an **Open** pill and no chevron; the others with their counts and a chevron. Tap to switch, as today. A project whose folder is gone shows **Folder missing**.
- **Edit mode:** Edit becomes **Done**; the two buttons hide; each row shows a small **×** instead of the chevron, and rows don't switch. × removes the row (above). Edit mode ends with Done, when the sheet closes, or when the list becomes empty.
- **No projects** (P7d, approved): the Current projects heading, the two buttons and "No projects yet".
- **Reopening a removed project:** Open project, then pick its folder (by default `Internal storage/Agentic Binning/<name>`). It comes back with everything it had. The same path recovers projects after a reinstall or cleared app data, because the list lives in app-private storage and the projects don't.
- **Copy stays short:** no explanatory paragraphs. The only text beyond labels is the one-line refusal under the buttons, the "Removed <name> · Undo" toast and the one confirmation title.

#### Acceptance tests

JVM (temp directories, no Android):
- `ProjectRegistryTest`:
  - `setCurrent_sameFolderByAnotherPath_addsNoDuplicate`;
  - `restore_putsTheEntryBackAtItsPosition`;
  - `remove_forgetsOnlyThatEntry`;
  - `remove_current_clearsCurrent`;
  - `remove_unknownPath_changesNothing`;
  - `remove_unreadableRegistry_refusesAndKeepsTheFile`, `create_unreadableRegistry_refusesAndKeepsTheFile`, `setCurrent_unreadableRegistry_refusesAndKeepsTheFile` (file bytes unchanged each time);
  - `remove_survivesANewInstance` (persistence after restart: a fresh `ProjectRegistry` on the same file sees the removal and the remaining current project);
  - `remove_preservesEveryFile` (a project folder with media, `.binning/project.db`, a journal and thumbnails: the path, size and SHA-256 of every file are the same before and after; the folder is never created when missing).
- `ProjectOpenRulesTest`: one case per row of the table above, plus `alreadyRegistered_opensTheEntry`, `insideOrContainingAProject_isRefused`, and that only "current" and "older" lead to an open.
- `ProjectSheetModelTest`:
  - `edit_showsRemoveOnEveryRowAndHidesTheTwoButtons`;
  - `edit_disabledWhileRegisteringOrCreating`;
  - `edit_endsWhenTheListEmpties`;
  - `remove_runningProject_asksFirst` and `remove_idleProject_removesAtOnce`;
  - `undo_restoresPositionAndReopensTheOpenProject`;
  - `removeCurrent_opensFirstOpenableProject_elseEmptyList`;
  - `noProjects_showsTheTwoButtons` (P7d).

Device (`src/androidTest/java/com/agentic/binning/project/ProjectOpenRemoveTest.kt`, 11 tests, run by direct instrumentation; fixtures under the app's external files dir, deleted afterwards; never a user project or the real `projects.json`):
- `probe_missingDatabase_createsNothing` (no `.binning/` afterwards).
- `openExisting_reopensSavedAnalysis`: a project with an analysis, tags, its FTS row and `stoppedByUser = 1`; the probe leaves the file byte-identical and decides Open; reopening keeps `project_meta` (name, `createdAt`), the Stop flag, the analysis, tags and FTS search.
- `openExisting_newerVersion_refusedAndByteIdentical` (`user_version = 99`).
- `openExisting_invalidFile_refusedAndByteIdentical` (a text file named `project.db`).
- `openExisting_foreignDatabase_refusedAndByteIdentical` (SQLite, but not Room's schema).
- `openExisting_noMetaRow_refused`.
- `openExisting_olderVersion_backsUpThenMigrates` (a v2 fixture from `MigrationTestHelper`; the backup is byte-identical to the original; the upgraded file then opens without a backup).
- `registry_sdcardAndStorageSpellings_areOneEntry` (`/sdcard/...` and `/storage/emulated/0/...`).
- `remove_preservesFiles` (media, database, thumbnails: same path, size and SHA-256 after removal, on shared storage).
- `remove_missingFolder_doesNotRecreateIt`.
- Covered by the phone check instead (see Deviations): removing the active project while it runs, the next project, removing the last project.

Phone check (Task 17's media, never Hawaii Photos or Maui):
1. Remove a project, force-stop the app (`adb shell am force-stop com.agentic.binning`), relaunch, and confirm it stays removed. Then bring it back with Open project, force-stop and relaunch, and confirm it is still there and current. Read the registry with `adb shell run-as com.agentic.binning cat files/projects.json`.
2. In Edit, tap × on the active project while it is analysing: "Stop analysis and remove …?" appears; confirm. The notification goes away, logcat shows the model released, and the next project opens (or, with none left, the sheet shows Current projects with the two buttons and "No projects yet"). Tap Undo within 5 seconds on another removal: the row comes back in place.
3. Rename a project folder on the PC side (`adb shell mv`), then remove the project; the renamed folder is untouched.
4. `adb shell ls -lR` of the project folder (and its media folder), before and after each removal, is identical.
5. Open project on a folder with no project ("No project in this folder"), on a project's own folder that is already listed (it just switches, no second row), and on the `.binning` folder of a removed project (it reopens). Compare the sheet with the mockup in dark and light with `screencap`.

- [x] **Step 1:** JVM tests (`ProjectRegistryTest` +13, `ProjectOpenRulesTest` 15, `ProjectSchemaTest` 1, `ProjectSheetModelTest` +10).
- [x] **Step 2:** `ProjectRegistry` canonical matching and strict writes, `remove` / `restore`, `ProjectOpenRules`; JVM tests pass.
- [x] **Step 3:** `ProjectOpenRemoveTest` on S26: **OK (10 tests)** after fixing `openExisting_noMetaRow_refused` (Room is lazy: `build()+close()` never created the file). 2a regression: `ProjectDatabaseTest` + `ProjectDatabaseMigrationTest` + `SortDaoTest` **OK (28 tests)**. Direct instrumentation only.
- [x] **Step 4:** Sheet compared on the phone with `screencap` in dark and light (`build/e2e/task6a/sheet-dark.png`, `sheet-light.png`). Matches the mockup: Current project, Open project / New project, Edit, OPEN pill. Other rows have no counts (documented deviation). P7d empty state (`No projects yet`) also captured.
- [ ] **Step 5:** Phone check on Sort Test (never Hawaii Photos or Maui as fixtures). **Done:** (1) remove persists across force-stop, Open project restores it and it stays current; (3)(4) `adb mv` then × leaves the renamed folder byte-identical (`ls -laR`); (5) empty folder → "No project in this folder" with no `.binning` created; already-listed folder switches with no second row. Dark/light screencaps as Step 4. **Not done:** × while a run is in progress (no pending scratch work; did not start the model); picking a hidden `.binning` folder (the system picker hides it). **Undo:** fixed in a follow-up (`sheetBottomInsetForUndo`: 84 dp inset outside the scroll; the toast swallows taps). Phone check: × on Sort Test, tap Undo, the row returned and Hawaii stayed current. Registry was restored from the pre-check `projects.json`; Hawaii/Maui/Sort Test databases stayed byte-identical to the baseline. Remaining Step 5 leftovers: × during a live run, and picking a hidden `.binning` folder.

### Task 7: Project Media data layer and edits

**Files:**
- Create: `db/ProjectMediaDao.kt`, `projectmedia/ProjectMediaRepository.kt`, `projectmedia/MediaFilter.kt`
- Modify: `db/ProjectDatabase.kt` (register the DAO as `abstract fun media(): ProjectMediaDao`). No schema change is needed (Task 6.1); if one turns out to be, it is v4 with `AutoMigration(3, 4)`, the exported `schemas/.../4.json` and a `ProjectDatabaseMigrationTest` case.
- Test: `src/androidTest/java/com/agentic/binning/projectmedia/ProjectMediaRepositoryTest.kt`

**Interfaces (proposed; nothing here exists yet):**
- `data class MediaRow(id, kind, durationMs, takenAt, status, categoryId, friendlyName, suggestedName, shotType, nameSetByUser, categorySetByUser, currentPath)`. `suggestedName` and `shotType` come from `analysis` (null for an item without one). The tile image is `ProjectFolders.thumbsDir(projectDir)/<id>.jpg` when that file exists, else `currentPath`, as `AnalyzeViewModel` does; there is no thumbnail column. `isBinned` is left out until Plan 3 decides it from `currentPath` (plan-2-notes).
- `fun observeRows(filter: MediaFilter, ftsQuery: String?): Flow<List<MediaRow>>`. Ordered by `takenAt`, oldest first, then `id` (Q17, approved). `ftsQuery` is only ever `FtsQuery.from(text)`; when it is set, the rows are the ids from `AnalysisDao.search` (raw `MATCH`, Task 6.6) joined back to `item`, and each gets a `matchedIn` field (name, category, tags, actions, description) for the tile's "Matched in" line (Q15, approved).
- `fun observeCounts(): Flow<MediaCounts>` and `fun observeChips(filter): Flow<List<ChipCount>>`. Chips: All, Waiting, Failed, then each category with a count. Waiting uses exactly Task 6.4's condition. Categories with no matches are hidden while a search is active.
- Edits, each `suspend` and one transaction that also refreshes the FTS row of every changed item:
  - `saveItem(id, name, category: CategoryChoice, unlockName, unlockCategory)`. `CategoryChoice` is `Existing(id)` or `New(name)`. Choosing a category on an `Analyzed` item makes it `Sorted`.
  - `moveItems(ids, category: CategoryChoice)`: sets `categorySetByUser` and status `Sorted` for `Analyzed` items.
  - `removeItems(ids)`: deletes the item rows. `analysis` and `item_tag` rows cascade; the `item_fts` row does not (no foreign key) and is deleted in the same transaction. 2a has no item delete to reuse.
  - `renameCategory(id, name)` and `mergeCategories(keep, drop)`: both set the surviving category's `createdBy = "user"` and rebuild the FTS rows of every item in the affected categories.
  - `retryFailed(id)`: back to `Pending` with `attempts = 0` and `failureReason = NULL` (2a's `ItemDao.returnToPending` sets the first two only). It also lifts a remembered Stop and starts analysis, through `AnalysisLauncher` like `userAdd` (P6, approved).
  - `setByHand(id, name, category)`: both locks on, status `Sorted`.
- **Unlock rule** (Task 6.4): unlocking a category clears `categorySetByUser` and keeps `categoryId`, so the item stays `Sorted` and only a Re-sort can move it. It never leaves an item locked with no category. The sheet says "Sorting may change this on the next Re-sort" (P2, approved).
- **FTS rebuild:** add a rebuild that left-joins `analysis` (empty description, actions and tags), so an item set by hand without an analysis is still found by name and category (Q19). 2a's `FTS_REBUILD_ROW` inner-joins `analysis` and stays as it is for 2a's own callers.
- New category names and renames go through `CategoryNames.clean` and `clash` (2a's functions, Task 6.5). A clash is an error result, not an exception.

- [x] **Step 1: Write the failing device tests:**
  - `rename_refreshesFtsRow`;
  - `remove_deletesFtsAndTagRows`;
  - `moveItems_setsCategoryLock`;
  - `newCategory_clashIsRejected`;
  - `merge_movesItemsAndDropsTheOther`;
  - `renameCategory_updatesFtsCategoryColumn`;
  - `setByHand_withoutAnalysis_isFoundByName`;
  - `unlockCategory_keepsTheItemSorted`;
  - `search_withFtsSyntax_doesNotThrow` (every input from `FtsQueryTest` through `FtsQuery.from` into the real `item_fts`);
  - `filters_kindShotDate`;
  - `retryFailed_resetsAttemptsAndReason`.
- [x] **Step 2: Run them on the phone** (`ProjectMediaRepositoryTest`).
- [x] **Step 3: Implement the DAO, filters and repository.** Shot type and the "Matched in" field come from `analysis` and `item_tag`; grid is one `item`/`analysis` join, FTS id lookup when searching. Left-join FTS rebuild for set-by-hand items. Retry lifts `stoppedByUser`; starting the pipeline is `onRetryFailed` (AppGraph, later).
- [x] **Step 4:** `ProjectMediaRepositoryTest` **OK (11)** on S26; 2a `SortDaoTest` + `ProjectDatabaseTest` **OK (24)**. Direct instrumentation only.
- [x] **Step 5: Commit** `feat: Project Media data layer and edits`

### Task 8: Lock rules in the sorter

**Files:** `src/test/java/com/agentic/binning/pipeline/CategorizerTest.kt` and `src/androidTest/java/com/agentic/binning/db/SortDaoTest.kt` (tests only; 2a's sorter needs no change).

2a already enforces the locks for automatic sorting (Task 6.1). Map each planned test to what exists:

| Planned | 2a coverage |
| --- | --- |
| `autoSort_neverReassignsLockedItem` | `SortDaoTest.applyChunk_skipsAnItemWhoseCategoryTheUserSet`, `uncategorized_excludesItemsWhoseCategoryTheUserSet` |
| `autoSort_neverRenamesLockedName` | `SortDaoTest.applyChunk_keepsUserName` |
| `autoSort_neverRenamesUserCategory` | No code path renames a category; nothing to test until Re-sort adds one (Task 9) |
| `autoSort_canAddNewItemsToUserCategory` | Not covered: write it |

- [x] **Step 1:** Write `SortDaoTest.applyChunk_canAddItemsToAUserCategory` (a `createdBy = "user"` category receives a model assignment and keeps its name and `createdBy`). That the prompt lists it as `locked` is already covered by `SortPromptTest.categoryLine_showsLockedOrOpen`.
- [x] **Step 2:** Run on S26: `applyChunk_canAddItemsToAUserCategory` **OK (1)**; full `SortDaoTest` **OK (18)**. No production change.
- [x] **Step 3: Commit.**

### Task 9: Categorize now and Re-sort as Sorting jobs

**Files:**
- Create: `projectmedia/ResortRequest.kt`
- Modify: `pipeline/Categorizer.kt` (`Categorizer`, `SortingCategorizer`), `db/SortDao.kt`, `pipeline/PipelineController.kt`, `pipeline/AnalysisLauncher.kt` (`RunKind`), `pipeline/AnalysisService.kt`, `AppGraph.kt`. No change to the model backends (`ClaudeTextModel`, `GenieXTextModel`).
- Test: `src/test/java/com/agentic/binning/pipeline/ResortJobTest.kt` (with `PipelineFakes.kt`), device: `SortDaoTest` cases for `prepareResort`.

- `data class ResortRequest(val model: SortingModelChoice, val keepEdits: Boolean)`.
- **Categorize now needs no new code path:** `AppGraph.startAnalysis(ref, RunKind.CategorizeOnly)` → `PipelineController.categorizeNow(project)` sorts only the waiting items (Task 6.2).
- **Re-sort needs these additions** (Task 6.8):
  1. `Categorizer.categorize(project, choice: SortingModelChoice? = null, onProgress)`: null keeps reading Settings. Update `CategorizerTest`'s callers.
  2. `SortDao.prepareResort(keepEdits: Boolean): Int`, one transaction as described in Task 6.8.2, returning how many items it made waiting.
  3. `PipelineController.resort(project, request): ResortStart` (`Started` or `Refused(reason)`), refused while `state.value.phase.isRunning` or for a project that is not current. It runs `prepareResort` and then `categorize(choice = request.model)` inside one launched run, so Stop, `stopAndReset`, the Categorizing phase and the sort notice behave as for any run. `RunKind.Resort` carries it through `AnalysisService`.
  - `resort` first checks that `request.model` can run (Claude: `ApiKeys.info` non-null; LFM3-1.7B: `ModelDownloader.isReady`) and returns `Refused(reason)` without clearing anything if not; only then does it run `prepareResort` (P1, approved). Test `resort_unavailableBackend_clearsNothing`.
- Failed, Missing and (in Plan 3) Binned items are left out.

- [x] **Step 1: Tests.** JVM `ResortJobTest` (8): `resort_keepsLockedNamesAndCategories`, `resort_withoutKeepEdits_clearsLocks`, `resort_usesTheChosenModel_notSettings`, `resort_isRefusedWhileARunIsInProgress` (analysing, paused and categorizing), `resort_isRefusedForAProjectThatIsNotOpen`, `categorizeNow_sortsOnlyWaitingItems`, `resort_releasesItsModelOnCancel`, `resort_unavailableBackend_clearsNothing`. Device `SortDaoTest`: `prepareResort_deletesEmptyModelCategoriesAndRefreshesFts`, `prepareResort_keepEdits_leavesLockedItems`, `prepareResort_withoutKeepEdits_clearsLocks`.
- [x] **Step 2: Implemented** (Nov 3):
  - `Categorizer.categorize(project, choice = null, onProgress)` is one method; null reads Settings, and `SortingCategorizer` never writes Settings.
  - `PipelineController.resort(project, request)` returns `Refused(RESORT_BUSY)` while `phase.isRunning` and `Refused(RESORT_NOT_OPEN)` for a project that is not current; otherwise it launches a categorize-only run that is never absorbed into a stopped run still cleaning up (it waits for it, like any run).
  - **Where the P1 check runs:** inside that run, holding the arbiter's Sorting job: `sortingAvailable(model)` first (if it returns a reason, nothing is cleared, the categorizer isn't called and the reason becomes the sort notice), then `prepareResort(keepEdits)`, then `categorize(choice = model)`. Clearing under the arbiter means nothing else sorts between the clearing and the Re-sort's own sorting. The check is `suspend` (DataStore, model files), so the controller can't return `Refused` for it synchronously; **`AppGraph.resort(ref, request)` (suspend) is the UI's entry and returns `Refused(reason)` for it before starting anything** (busy, not open, `sortingUnavailable`: Settings' "Add your API key to use Claude" or "Download LFM3-1.7B in Settings first", or the service refused to start). It is never queued or deferred.
  - `RunKind.Resort` carries the model and Keep my edits through `AnalysisService` (intent extras `run_kind`, `resort_model`, `resort_keep_edits`); the service calls `controller.resort` and logs a refusal.
  - Refusal lines are in `projectmedia/ResortRequest.kt` (`RESORT_BUSY`, `RESORT_NOT_OPEN`, `RESORT_NOT_STARTED`) for Task 15 to reuse.
- [x] **Step 3:** `./gradlew testDebugUnitTest` 496 tests, 0 failures (`PipelineControllerTest`, `CategorizerTest`, `ModelArbiterTest` green). On S26 by direct instrumentation: `SortDaoTest` **OK (21)**, `ProjectDatabaseTest` **OK (7)**, `ProjectMediaRepositoryTest` **OK (11)**; the app launches with no crash. **Not run:** a real Re-sort on the phone; nothing calls `AppGraph.resort` until Task 15's sheet (checked in Task 17, LFM3-1.7B only).
- [x] **Step 4: Commit.**

### Task 10: Status and counts wiring

**Files:** `projectmedia/ProjectMediaRepository.kt`, the Analyze screen's Project Media pill.

- [x] **Step 1:** Map 2a's statuses into `MediaCounts`: `pending` = `Pending`; `analyzing` = `Analyzing`; `waiting` = Task 6.4's condition; `failed` = `Failed`; `missing` = `Missing`; `readyToBin` = `Sorted`; `binned` = `Binned` (0 until Plan 3). Map `PipelineState.phase` to `ActiveJob`: `Categorizing` → `Sorting`; any other running phase (`LoadingModel`, `Preparing`, `Describing`, `Paused`, `Releasing`) → `Analysis`; `Idle` / `Stopped` → null. Do not use `ModelArbiter.active` (Task 6.3). Note that 2a's `ItemCounts` lumps statuses differently (`failed` includes Missing); don't reuse it.
  - Done Nov 3 in `projectmedia/MediaStatus.kt`: `mediaCountsOf(items, status, waiting)` (one bucket per status, Missing apart from Failed), `isWaiting(status, categoryId, categorySetByUser)` (Task 6.4's condition, now the only copy; `ProjectMediaRepository` uses both), and `activeJobOf(phase)`. JVM `MediaCountsTest` (5).
- [x] **Step 2:** No change needed: the Analyze bar's Project Media count is the project's item total (`items().observeAll().size`), which is `MediaCounts.total` by definition (both count every `item` row). The button stays disabled until Task 11 navigates from it.
- [x] **Step 3:** `testDebugUnitTest` 501 tests, 0 failures; `ProjectMediaRepositoryTest` **OK (11)** on S26; app launches. **Commit.**

### Task 11: Project Media screen shell

**Files:** `ui/media/ProjectMediaViewModel.kt`, `ui/media/ProjectMediaScreen.kt`, `ui/media/MediaTile.kt`, `ui/media/MediaIcons.kt`; modify `MainActivity.kt` (navigation from the Project Media pill; back returns to Analyze).

Match mockup states 1, 2, 9 and 10, and the status line strip:
- **Header:** back, project name, "Project media · N items", settings.
- **Search field** with a clear button. Debounce about 150 ms, then `FtsQuery.from`.
- **"CATEGORIES · N"** with Re-sort and Edit.
- **Chips row:** All, then amber Waiting and Failed when there are any (Q9, approved), then the categories, with a lock on yours.
- **Filters (Q3, option C):** one row under the category chips: an **All · Photos · Videos** toggle, then a chip for each set shot type or date filter (blue, with × to clear), then a **Filters** button that opens a sheet with Shot type and Date (each choice with its count) and "Show N items". Mockup `docs/superpowers/mockups/2026-11-01-plan-2b-filters-options.html`, option C.
- **Result line** with Select.
- **3-column grid of tiles** (Coil thumbnails; badges per Q16, approved: duration, Waiting / Failed, no locks).
- **Dock:** the `mediaStatus` text with Categorize now, Analyze, and Bin Files. Categorize now calls `AppGraph.startAnalysis(ref, RunKind.CategorizeOnly)` (Task 6.2); like Re-sort it is disabled, with the reason, while a run is in progress (Q12, approved). Disabled Bin Files shows the reason in a toast; enabled Bin Files shows "comes with Plan 3" until Plan 3.
- **Empty project:** a card with Add a folder and Pick photos and clips, reusing the project sheet's pickers.

- [x] **Step 1:** `ProjectMediaViewModel` over a `MediaSource` (the app's is `RepositoryMediaSource`); JVM `ProjectMediaViewModelTest` (13): default view, chip toggling, attention chips at zero, kind toggle / shot type / date with counts, closing the sheet drops the draft, the 150 ms search debounce through `FtsQuery.from` (clearing is immediate), "Matched in", select mode (Select all respects the filter, Clear), removed items leave the selection, the dock and Q12 refusals, empty project / no project, a project switch resets the screen. `MediaStatusTest` adds `binFilesReason_namesWhatBlocksIt`.
- [x] **Step 2:** Compose: `ProjectMediaScreen.kt` (header, select header, search, CATEGORIES · N with Re-sort and Edit, chips, Q3 option C filters row and Filters sheet, result line, 3-column grid, dock with status line, empty card), `MediaTile.kt`, `MediaIcons.kt`; `MainActivity` gets a Media screen (the Analyze pill opens it; back returns; Settings returns to whichever screen opened it). Shared pieces: `MediaFilter.matches` (the repository's filter, now used by both), `binFilesBlockedReason`, `ui/glass/MockupScale.kt`.
  - Deviations (ledgered): no `@Preview`s (none exist in the repo; dark and light are compared on the phone); a fixed header column above the grid instead of the mockup's scroll-under top fade (the tiles do scroll under the dock, as mocked); select mode keeps the dock until Task 13's bulk bar; tile tap, Edit and idle Re-sort do nothing until Tasks 12, 14 and 15; enabled Bin Files says "Bin Files comes in a later version".
  - `testDebugUnitTest` 515 tests, 0 failures; `assembleDebug` succeeds.
- [x] **Step 3:** On S26 with Hawaii Photos (read only; nothing edited), opened from the pill, `screencap` in `build/e2e/task11/`: default (state 1) dark and light, one category (state 2) dark and light, search "turtle" (state 9: categories with no match drop out, "Matched in category / tags"), the Filters sheet with counts, Close-up applied (blue chip with ×, counts follow), select mode with two ticked. Matches the mockups apart from the deviations above. Found and fixed: a configuration change (theme switch) dropped back to Analyze, so `MainActivity` keeps the screen with `rememberSaveable`. **Glass blur fix:** tiles under the dock showed through sharp. The blur worked, but its picture was translucent: the backdrop paints its base colour outside its Haze source (only the blobs are recorded) and a tile grid's gaps are transparent, so the real grid showed through. `GlassSurface` now gives the effect `backgroundColor = tokens.bg`, so the blurred picture is opaque; the grid is a Haze source at `zIndex = 1` and scrolls under the dock (owner's choice, Nov 3). Analyze looked unchanged. Empty project (state 10) checked with a new project "Task11 Empty": matches the mockup; Add a folder opened the system picker (cancelled). It was then taken off Current projects; its folder (an empty `.binning`) is still on the phone. The app theme was set back to Dark and Do Not Disturb back off afterwards.
- [x] **Step 4: Commit.**

### Task 12: Detail sheet and category picker

Match mockup states 3 and 3b.

- **Media and badges:** the media, its duration badge, and a Sorted / Waiting badge.
- **Friendly name:**
  - An editable field.
  - A lock marker: "From sorting", or "Set by you" with an Unlock link (Q5), or "Set by you when you save" while an edit is pending.
  - The hint "File name when binned: …" and the model's suggestion.
- **Category:** a row with the same marker that opens the picker. **New category…** comes first and runs the clash check inline.
- **Details:** description, Actions timeline (videos), tags, shot type, taken, original file, location.
- **Remove from project**, Cancel, and Save. Save is enabled only when something changed.

- [x] **Step 1:** JVM `DetailSheetModelTest` (9: markers From sorting / Set by you + Unlock / Undo / Set by you when you save / Not placed yet, Save enabled, trimmed name, file name hint and suggestion, picks, callouts, details, video actions) and 8 more `ProjectMediaViewModelTest` cases (open, Save sends the draft, Cancel drops it, pending and failed tiles, picker counts and selection, New category… clash inline, Save clash keeps the sheet, Remove confirms then removes, a switch closes the sheet). Device: `ProjectMediaRepositoryTest` adds `saveItem_categoryOnly_leavesTheNameUnlocked` and `detail_readsAnalysisTagsAndCategory`.
- [x] **Step 2:** `DetailSheetModel.kt` (pure: draft, markers, `saveRequest`), `DetailSheet.kt` (detail sheet, category picker, `RemoveDialog` for Task 13 to reuse), view model wiring; `ProjectMediaRepository.detail(id)`.
  - **Fixed in `saveItem`:** it locked the name on every Save, so a category-only change locked a name the user never touched (Q5). It now locks the name only when it changed, and runs in one transaction (`useWriterConnection` + `immediateTransaction`; `withTransaction` needs the SupportSQLite helper, which the bundled driver doesn't configure).
  - Deviations (ledgered): tapping a Pending or Analyzing tile says "Details open once this item is analyzed"; Failed and Missing tiles do nothing until Task 16's sheet; no Binned callout or "Save and move file" (Plan 3); the play badge on a video doesn't play it.
- [x] **Step 3:** S26, on Sort Test (backed up first, restored byte-identical afterwards): renamed Dog Label to "Brown Dog Card" (marker "Set by you when you save", Save on), search "brown dog" found it; Cat Text's category changed to Nature through the picker with the name still "From sorting"; New category… "Foods" showed "That is the same as “Food”…"; the remove dialog opened and was cancelled. The pulled database showed `nameSetByUser = 1` only on the renamed item and `categorySetByUser = 1` with `nameSetByUser = 0` on Cat Text; 50 items. **Not done:** Categorize now after the rename (Sort Test has nothing waiting, so it isn't offered); the sorter's name lock is covered by `SortDaoTest.applyChunk_keepsUserName` and `ResortJobTest.resort_keepsLockedNamesAndCategories`. Screens in `build/e2e/task12/`.
- [x] **Step 4: Commit.**

### Task 13: Select mode, bulk move and remove

Match mockup states 4 and 4b.

- **Entering and leaving:** long-press a tile, or tap Select. The header becomes ✕, "N selected", and Select all (it respects the current filter).
- **Bulk bar:** Move to category (the picker, then "Save · move N") and Remove from project.
- **Remove dialog:** "The files stay on your phone where they are now…". From Plan 3 on, it adds the line about files already in a category folder (Q2).

- [x] **Step 1:** Selection with filters was already covered in Task 11. New `ProjectMediaViewModelTest` cases (6): move picks a category then saves the selection (nothing ticked first, "Save · move N"), move to a new category (clash inline), Cancel keeps the selection and the bar is off with none ticked, "Moved 1 item. 1 not analyzed yet stayed where it was.", bulk remove asks then removes and leaves select mode, a single item is named in the dialog. Device: `moveItems_skipsItemsNotAnalyzedYet` (seen failing first: the pending item got the category).
- [x] **Step 2:** `SelectionBar.kt` (replaces the dock in select mode); the category picker is shared (`PickerSheet` + `PickerActions`) with a "Save · move N" footer for bulk move; `RemoveDialog` takes its button label ("Remove N").
  - **Fixed in `moveItems`:** it gave a category (locked) to pending, failed and missing items without changing their status, leaving them neither waiting nor ready to bin. It now skips items that aren't analyzed (`canTakeACategory`) and runs in one transaction; the view model's toast says how many stayed.
  - `testDebugUnitTest` 538 tests, 0 failures; `ProjectMediaRepositoryTest` **OK (14)** on S26.
  - Phone check on Sort Test (backed up, restored byte-identical): long-press then tap ticks two ("2 selected", bulk bar); Move 2 items picker matches state 4; Save moved both to Food (pulled database: `categorySetByUser = 1`, names untouched, select mode closed); "Remove 2 items from Sort Test?" (state 4b) removed both rows with their analysis and tag rows, and both files were still on disk. "Remove from project" was cut short on the phone; the bulk buttons now use 13 sp and less padding, checked again. Screens in `build/e2e/task13/`.
- [x] **Step 3: Commit.**

### Task 14: Category Edit sheet

Match mockup states 5a and 5b; the Save progress (state 6) comes with Plan 3.

- Rename runs inline with the clash check; a "Renamed from …" note shows the pending change.
- Tick exactly two categories to merge (Q10, approved). The merge box asks which name survives. In 2b no category has a folder, so the file consequences in the mockup show only from Plan 3 on.
- Save applies every staged rename and merge in one transaction.

- [x] **Step 1:** JVM `CategoryEditModelTest` (8): staged rename with its note, clash against staged names (and a renamed-away name is free), back to the saved name is no change, two ticks open the merge box and a third is off, a staged merge hides the dropped one and adds its count, a rename of the dropped one is left out, Cancel unticks. `ProjectMediaViewModelTest` +4 (stage and save together, off while a run is in progress, Save clash keeps the sheet, Close drops the staging). Device: `applyCategoryEdits_renamesAndMergesInOneSave` (FTS finds the new name, not the old), `applyCategoryEdits_swapsTwoNames`, `applyCategoryEdits_clashWithAnUnchangedName_changesNothing` (the merge staged with it doesn't happen either).
- [x] **Step 2:** `CategoryEditModel.kt` (pure staging), `CategoryEditSheet.kt`, view model wiring; the Edit button opens it. **Save goes through a new `ProjectMediaRepository.applyCategoryEdits(renames, merges)`, not `renameCategory` / `mergeCategories` one by one:** it checks the names after Save first (so two names can be swapped and a clash changes nothing), then applies merges and renames in one transaction and rebuilds the touched items' FTS rows.
  - Deviations (ledgered): Edit is off, with "Edit is available when analysis is stopped or done", while a run is in progress (the sorter matches categories by name); no file consequences or folder notes (no folders in 2b); the merge box defaults to keeping the first ticked name.
  - `testDebugUnitTest` 550 tests, 0 failures; `ProjectMediaRepositoryTest` **OK (17)** on S26.
  - Phone check on Sort Test (backed up, restored byte-identical): state 5a list; renaming Subway Sign to "Foods" showed "Same as “Food”. Tick both to merge instead." with Done off; "Subway Station" staged with "Renamed from Subway Sign"; ticking Video and Photo opened the merge box (5b); keeping Photo, then "Save 2 changes". Pulled database: Subway Station and Photo `createdBy = user`, Video's five items in Photo, Video gone, 7 categories, 50 items; search "subway station" found the item by its category. Found and fixed: the clash line sat under the keyboard; the editing row now brings itself into view. Screens in `build/e2e/task14/`.
- [x] **Step 3: Commit.**

### Task 15: Re-sort sheet

Match mockup state 7.

- **Model picker:** lists exactly what Settings lists, defaulting to the Settings choice (Q14, approved). Today that is `SortingOptions` in `ui/settings/SettingsModel.kt`: Claude and LFM3-1.7B. Reuse `pickSorting` so a missing key or an undownloaded model is handled as in Settings (`ApiKeys.info`, `ModelDownloader.isReady`).
- **Keep my edits:** ticked by default (Q4, approved), with counts of what it keeps.
- **"Changes" and "Doesn't change now"** panels.
- **No cost note** (Q13, approved). The sheet doesn't estimate Claude's cost.
- **The button:** "Re-sort N items", disabled with the reason while a run is in progress (Q12, approved): "Re-sort is available when analysis is stopped or done". (Q12's draft text said "paused or done", but a paused run still holds the vision model.) Also disabled, with `pickSorting`'s reason, when the chosen backend can't run (P1).

- [ ] **Step 1:** JVM tests for the sheet's state (default model, Keep my edits counts, disabled reasons).
- [ ] **Step 2:** Compose; wire to Task 9.
- [ ] **Step 3: Commit.**

### Task 16: Failed item sheet

Match mockup state 8: the reason, attempts, Retry analysis, Set name and category by hand, and Remove from project (Q19, approved). A failed item set by hand gets both locks and is sorted like any other.

- [x] **Step 1:** Compose; wire to `retryFailed`, `setByHand` and `removeItems`.
  - `FailedSheetModel.kt` (pure: reason in words, attempts, by-hand form, `SetByHandRequest`), `FailedSheet.kt`; the view model reuses the detail session (`manual` for the form) and its picker and remove dialog. JVM: `FailedSheetModelTest` (7), `ProjectMediaViewModelTest` +6 (failed and missing tiles open it, Retry, by hand with an existing and a new category, Back drops the form, clash keeps it, Remove).
  - Retry wires `ProjectMediaRepository`'s `onRetryFailed` hook, which Task 7 left unwired, to `AppGraph.startAnalysisAfterRetry` (the add path: lifts a Stop, analyzes what is waiting; P6).
  - Deviations (ledgered): a Missing item opens the same sheet, titled "File missing", with Remove only; the Bin Files row says "Not blocked. This item stays where it is." without the mockup's "listed in the report" (Plan 3).
  - `testDebugUnitTest` 563 tests, 0 failures; `ProjectMediaRepositoryTest` **OK (17)** on S26. Phone check in Task 17; it found the file name shown uppercased (a `Section` heading), fixed.
- [x] **Step 2: Commit.**

### Task 17: End-to-end check on the phone

- [x] **Step 1:** Use fresh media (the Plan 1 set belongs to "Plan1 E2E"). Create a project, add a folder, and let analysis and sorting finish. Don't use Hawaii Photos or Maui as a reset fixture; if the owner asks to reset one, back it up first and keep user-set values ("Handoff notes").
- [x] **Step 2:** Edit in order:
  1. Rename one item.
  2. Bulk-move three items to a new category.
  3. Rename one category, then merge two.
  4. Remove one item.
  5. Search for the renamed item's new name and for an old name.
  6. Run Re-sort with Keep my edits ticked: with LFM3-1.7B, and with Claude only once the owner provides a billed Anthropic key (the authenticated Claude test is deferred; never ask for a key or make a paid call without that).
- [x] **Step 3: Expect:**
  - Every locked value is unchanged after the Re-sort.
  - Search finds new names, not old ones.
  - The removed item's file is still on disk.
  - No file moved: `adb shell ls -lR` of the media folder matches before and after.
  - The locks are in the database: pull it with `MSYS_NO_PATHCONV=1 adb pull "/storage/emulated/0/Agentic Binning/<name>/.binning/project.db" build/project.db` (journal mode TRUNCATE, so a pull between writes is complete), then `sqlite3 build/project.db "select friendlyName,categoryId,nameSetByUser,categorySetByUser from item; select id,name,createdBy from category;"`.
- [x] **Step 4: Commit** the notes into `CLAUDE.md`.

**Result, Nov 3** (build: Task 16 with the one-word category fix, PR #18, merged on a scratch branch; LFM3-1.7B; S26). Evidence in `build/e2e/task17/` (local).
- Media: 14 Plan 2a picture cards re-encoded with ffmpeg (new hashes), one clip and a 3 KB truncated JPEG, in `Movies/avb-2b-task17`. "Task17 E2E" made with New project and "A folder".
- First run: 15 Sorted, `t17_broken.jpg` Failed `decode`; `sort_run` 1 `done`, `sort-v4`, 6 one-word categories (Nature, Food, People, Video, Transport, Sports).
- Failed sheet: reason, "Tried once.", three actions. Retry: the item went Pending with attempts 0, the service started, it failed `decode` again. Set by hand "Broken Card" in Food: Sorted, both locks, found by FTS.
- Edits: "Cat Text" renamed "Tabby Cat"; Tabby Cat, Dog Portrait and Puppy Portrait moved to a new "Animals" (user's); Transport renamed "Vehicles"; Sports merged into Nature (Nature kept, now the user's); Pizza Label removed (row, FTS and tag rows gone; file stays).
- Search: "tabby" finds the item; the FTS name column no longer holds "cat text" (the UI search "cat text" still finds it through "Cat" in the new name and "text" in its description); "transport" finds nothing; "vehicles" finds the taxi, "Matched in category".
- Re-sort (LFM3-1.7B, Keep my edits, started with the service intent because Task 15's sheet is not built): `sort_run` 2 `done`, 11 re-sorted, the 4 category-locked items kept. **Every locked name and category unchanged**; the user's categories Nature, Vehicles and Animals kept; every FTS row matches its item's name and category; `integrity_check` ok; the media folder's `ls -lR` identical before and after; logcat shows LFM3-1.7B released; no `sk-ant`.
- Finding: renaming a category kept the model's old description, so Vehicles kept the description "Transport". The Re-sort then made a new model category "Transport" beside the user's "Vehicles" and moved the unlocked taxi into it. **Fixed Nov 3:** a rename clears the description (`ProjectMediaDao.renameCategory`; device tests `renameCategory_clearsTheModelsDescription`, `applyCategoryEdits_renameClearsTheDescription_mergeKeepsTheKeptOnes`; `ProjectMediaRepositoryTest` OK (19)). Phone recheck: Vehicles renamed "Rides" stored `description = ''` and the Re-sort prompt listed `Rides 0 locked` with none. LFM3-1.7B still filed the taxi under a new "Transport" (Rides was empty, and Food's self-talk description says "taxi sign is transport"): model quality, not the stale description. That run was `partial` (10 of 11; one item left out by the validator, retried on the next run).
- LFM quality (no change made): the Re-sort renamed Burger Label to "Beach Label" in Nature, put the clip in Vehicles and the soccer card in Food; Food's description holds model self-talk.
- Phone left on Hawaii Photos, LFM3-1.7B selected, Do Not Disturb off. "Task17 E2E" and `Movies/avb-2b-task17` remain; Sort Test's database is byte-identical.

---

## Left for Plan 3 (binning)

- The move engine around `MovePlanner` and `LayoutCheck`:
  - write the plan in a transaction (`move_run`, `move_step`);
  - copy, verify size and hash, update `currentPath`, delete the original;
  - resume after a crash or reboot;
  - remove a category folder only when it is truly empty;
  - update MediaStore.
- Bin Files and its progress and report.
- Save moving files that are already binned (mockup state 6): the progress card, "Other edits wait", the Saving job kind. A category rename can be done as per-file moves into the new folder plus removing the empty old one. That keeps it resumable with the existing `move_step` table instead of a separate folder-rename step.
- Q2's removed-file record: a `removed_file(path, removedAt)` table filled by `removeItems` for binned items, read by `LayoutCheck` as `ignored`.
- Decide "already binned" from `currentPath` against the target path, not from `status` alone (plan-2-notes).
- **Q11 (deferred Nov 1):** renaming or merging a category whose files are binned moves files at Save; decide with the move engine whether the user may leave the screen while it runs.
- **P8 revisit:** once binned category folders exist, decide whether registration should skip any folder holding `.binning/project.db`, so a project taken off Current projects can't have its binned files added to another project; and how to handle items that end up in two projects after a removed project is reopened.

---

## Handoff notes

Everything from the Oct 31 handoff (`docs/superpowers/plan-2b-handoff.md`, deleted in the commit that finished Task 6) that still matters, with what changed since.

- **Order of work (Nov 1):** the owner reviews PR #5 locally and merges it; Task 6A (Open project, Edit/× on Current projects) is then built on a fresh branch with its own PR, before Task 7. Nothing from Task 6A onward is started. Q3 (filter placement) must be answered before Task 11.
- **Branches and PRs:** PR #2 (mockups) and PR #4 (Plan 2a) are merged into `main`. PR #3 (this branch's first draft PR) is closed and must not be reopened or retargeted; `plan-2b-draft` has a new draft PR against `main`, jdtimothy/Agentic-Video-Binning-Android#5. Never rebase or force-push `plan-2b-draft`: merge `main` into it.
- **Mockups:** `docs/superpowers/mockups/2026-10-31-plan-2b-project-media.html` (open the file in a browser; GitHub doesn't render it), checked in Chromium at 390 px and 1440 px. Questions: `docs/superpowers/mockups/2026-10-31-plan-2b-questions.md`.
- **Scope decided Oct 31:** files never move in Plan 2b; Save changes the database only. The move engine, Save moving binned files, the save progress card (mockup state 6) and Q2's removed-file table are Plan 3. `move/MovePlanner.kt` and `move/LayoutCheck.kt` were written early because they are pure logic.
- **Approved answers:** Q2 (a removed binned item's file stays, its path is remembered so the count check skips it), Q5 (user-set values are locked, with Unlock), Q7 (waiting items keep blocking Bin Files; the ways out are the Waiting chip, Categorize now and setting a category by hand).
- **Cost:** there is no Re-sort cost note (Q13, Nov 1); the mockup's "about $0.05" note is obsolete and `ResortCost` was deleted.
- **Search format** (verified Oct 31 against SQLite FTS5 with `tokenize='porter unicode61'` in Python): every quoted word is safe; raw `AND`, `-x`, `a:b`, `(`, `*` and a stray `"` fail as queries; `"resting"*` also matches "restaurant", hence the prefix only on the last word.
- **Deferred Claude test:** 2a merged with the authenticated Anthropic request unverified, by the owner's explicit choice (`notes/2026-11-01-plan-2a-phone-recheck.md`). Keep Claude implemented and LFM3-1.7B selected for daily use. The test needs an Anthropic API key with usable billing; Nous Portal or X subscriptions don't provide one, and another provider would be separate scope. Never request a key, make a paid call or swap the provider without the owner.
- **Hawaii Photos:** when the Claude test resumes, keep all 53 items and reset only their model-generated sorting, after a fresh backup; never restore the old 46-item baseline. Preserve user-set names, assignments and categories.
- **Cloud sessions:** the container now has OpenJDK 21 and the Android SDK at `/root/android-sdk` (gitignored `local.properties`); `ANDROID_HOME` is unset. `./gradlew` works directly since `ea5a67c` (before that it was committed without the executable bit and had to be run as `bash ./gradlew`). Maven Central rate-limits these containers (HTTP 429): retry with backoff; the Gradle cache fills a little on each attempt. No phone is attached, so device checks stay pending. Never commit `local.properties`, the SDK, keys or build outputs.
