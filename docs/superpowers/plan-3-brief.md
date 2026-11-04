# Plan 3 brief: binning (for a cloud session)

Written Nov 3, 2026 for a cloud Claude session on branch `plan-3-binning`. The
owner's phone (Galaxy S26 Ultra) is **not** attached in the cloud. Your job is
to get Plan 3 as far as it can go without the phone, then stop with a clear
handoff.

## What Plan 3 is

Binning moves files. **Bin Files** moves every sorted item to
`<project>/<Category>/<friendly name>.<original extension>`. A **Save** that
renames, re-categorizes or merges something already binned moves its files the
same way, right away. Plans 1, 2a and 2b are merged into `main`: analysis,
sorting (LFM3-1.7B or Claude), Project Media, editing, locks and Re-sort all
exist. Nothing has moved a file yet.

Vocabulary the owner insists on: an item is **sorted** once it has a category
and **binned** once its file is in its category folder. The button is
**Bin Files**.

## Before you start

Merge `origin/main` into `plan-3-binning`. PR #22, a tidy-up that edits
`CLAUDE.md`, may have landed after this brief was written. Merging it first
avoids a conflict when you update `CLAUDE.md` at the end.

## Read first, in this order

1. `CLAUDE.md`: project rules, build commands, the cloud build setup (OpenJDK
   21, SDK at `/root/android-sdk`, Maven 429 retries).
2. `docs/superpowers/specs/2026-10-28-agentic-binning-design.md`, especially
   "4. Bin Files (user-started) and Save moves", "Data model", "Storage and
   permissions", Goals, Non-goals and Anti-goals. This is the binding spec.
3. `docs/superpowers/plans/2026-10-31-plan-2b-project-media.md`:
   - "Left for Plan 3 (binning)" at the end: your input list.
   - "Product decisions": Q11 is deferred to you; P8 needs revisiting.
   - "Global Constraints", "Device tests", and the format of the whole plan.
     Plan 3 should read like it.
4. `docs/superpowers/plan-2-notes.md` (the decisions carried between plans).
5. `docs/superpowers/mockups/2026-10-31-plan-2b-project-media.html`: state 6
   ("Save in progress", files moving one by one) and the status line and Bin
   Files button rules. Match its look for any new mockups.
6. The code already written for this:
   - `src/main/java/com/agentic/binning/move/MovePlanner.kt` and
     `LayoutCheck.kt`, with their JVM tests in `src/test/.../move/`.
   - `db/Entities.kt` (`MoveRunEntity`, `MoveStepEntity`, already in schema v3)
     and `db/ProjectDatabase.kt`.
   - `projectmedia/MediaStatus.kt` (`MediaCounts`, `binFilesBlockedReason`,
     `ActiveJob.Binning`).
   - `ui/media/ProjectMediaViewModel.kt` (`onBinFiles` is a placeholder).
   - `pipeline/PipelineController.kt` and `ModelArbiter`: one job at a time.
   - `pipeline/AnalysisService.kt`: the foreground service a move must run in.
7. `Instructions.md`, only if you touch anything model-facing (you shouldn't
   need to).

## Rules (do not break these)

- **Files are never lost or overwritten.** Copy, verify size and SHA-256, update
  `currentPath` in the database, then delete the original. At every moment
  each file exists at its source or verified at its destination. A name clash
  gets ` 2`, ` 3` and so on. No undo. A category folder is removed only when
  it is truly empty.
- **Resumable.** The plan is written to `move_run` / `move_step` in one
  transaction before the first move, and each step's state is recorded, so a
  crash or reboot resumes where it stopped.
- **Remove from project never deletes a file** (Q1). Failed and missing items
  are never moved and never block Bin Files.
- **Schema migrations never destroy data.** Any new column or table gets an
  `AutoMigration`, a committed schema JSON and a `MigrationTestHelper` test.
- **One job at a time.** Binning goes through `ModelArbiter` and the pipeline's
  state like Re-sort does. It is disabled with a reason while another run is
  going, never queued.
- **Don't change pinned versions** (AGP, Kotlin, Gradle, compileSdk 35,
  targetSdk 34, minSdk 31, GenieX), repositories or `jniLibs` packaging. On
  Maven 429s, retry with backoff.
- **No device tests can run here.** Write device tests (`src/androidTest`) where
  real files, MediaStore or Room migrations are involved, and make sure
  `assembleDebugAndroidTest` compiles them. Mark each one in the plan as
  "written, not yet run on the phone". Never use `connectedDebugAndroidTest`.
- **No Claude API calls.** There is no key here, and paid calls need the owner.
- **Product decisions are the owner's.** Where the spec doesn't decide
  something, write it down as a question with your recommendation, and build
  only what doesn't depend on the answer.
- **Git:** work on `plan-3-binning` only. Commit per task with clear messages.
  Push. Never push to `main`, never merge, never force-push.

## What to do

### Phase 1: questions and mockups

1. Write `docs/superpowers/mockups/<date>-plan-3-questions.md`. For each open
   decision give the choices, your recommendation and what it changes. Cover
   at least:
   - Q11: can the user leave the screen while files move?
   - P8: should registration skip folders that hold a `.binning/project.db`?
     And what happens to items that end up in two projects?
   - Q2: the `removed_file` record that `LayoutCheck` reads as ignored.
   - MediaStore updates after moves (scan the new path, remove the old one).
   - Verify step failures: what the user sees, and what they can do next.
   - Stop and pause during Bin Files.
   - Storage-full mid-move.
   - What the report lists.
   - Bin Files when nothing needs to move.
2. Make clickable HTML mockups in the 2b style (dark and light, same glass
   look) for:
   - Bin Files confirm, if any;
   - progress;
   - the report, including the left-out failed and missing items;
   - a verify mismatch;
   - resuming after a crash.

   State 6 (Save in progress) already exists; reuse its look.
3. Commit and push. The owner reviews these before anything that depends on
   them ships.

### Phase 2: the plan

Write `docs/superpowers/plans/<date>-plan-3-binning.md` in the same format as
the Plan 2b plan:
- a status line, Goal and Architecture;
- Global Constraints, Review Focus, Device tests, Product decisions (open ones
  marked) and File Structure;
- then numbered tasks, each with Files, Interfaces, Steps (test first, with
  the command and its Expected output) and a Commit step.

Mark every task **phone: yes/no**. End with a phone end-to-end task like 2b's
Task 17: a fresh project, Bin Files, a crash mid-move (force-stop), resume,
Save on a binned item, `ls -lR` before and after, and SHA-256 of every file.
Commit and push.

### Phase 3: build what needs no phone and no open answer

Work through the tasks marked phone: no whose decisions are settled. For
example:
- the move engine's pure logic and its state machine, with fakes for the file
  system;
- the DAO for `move_run` / `move_step`;
- resume logic;
- the verify step built on `LayoutCheck`;
- view model state for Bin Files.

For each one:
- write the test first and watch it fail;
- run `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`;
- commit and push.

Keep a ledger in `docs/superpowers/notes/plan-3-cloud-handoff.md`. It must be
committed: `.superpowers/` is gitignored and won't reach the owner. Each entry
is either:
- `Ruling: <what you decided> — <why> — <cost if wrong>`, or
- `Task N: complete (commits …, tests: …)`.

Don't build UI whose mockup isn't approved yet. Stop at that line.

### Finish

1. Complete `docs/superpowers/notes/plan-3-cloud-handoff.md`:
   - what is done and how it was verified;
   - every ruling;
   - the questions still open for the owner;
   - the exact device tests to run on the phone
     (`adb shell am instrument -w -e class <Class> com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner`);
   - the phone checks still to do;
   - what the next session should do first.
2. Update the Plan 3 status line and `CLAUDE.md`'s "Current state".
3. Push and open a **draft** PR from `plan-3-binning` into `main`. Put the open
   questions in the PR description.
