# Plan 3: open design questions for binning

Nov 4, 2026 · goes with `2026-11-04-plan-3-binning.html`

Answered on Nov 4 by the owner: B3, B5, B10 and P8. Everything else is open.

These are the decisions the spec leaves open, the two carried from Plan 2b
(Q11 and P8), and the ones the move engine had to make before it could be
planned. Most important first. Each has the choices, a recommended answer and
what the answer changes. The mockups show the recommended answer unless the
question says otherwise. "State N" refers to the numbered states in the HTML
file; "2b state N" to the Plan 2b mockups.

Numbering: Q2, Q11 and P8 keep their Plan 2b numbers. New questions are B1,
B2 and so on, so they never clash with 2b's Q1–Q19.

## Decisions so far

| # | Question | Status |
| --- | --- | --- |
| B1 | Copy, check, then delete; never a plain rename | Open (recommend: copy, as the brief says) |
| Q11 | The user may leave the screen while files move | Open (recommend: yes; B3's answer assumes it) |
| B2 | Stop during Bin Files finishes the current file; no Pause | Open (recommend: Stop after this file) |
| B3 | A crashed run resumes on its own when the project opens | **Approved Nov 4** (as recommended) |
| B4 | A copy that doesn't match its original stops the run | Open (recommend: retry once, then stop) |
| B5 | Folder check before and after; extra files block until added or ignored | **Approved Nov 4** (as recommended) |
| B6 | Storage full: check before each file, stop cleanly | Open (recommend: as described) |
| B7 | A confirm sheet before Bin Files | Open (recommend: yes) |
| B8 | What the report lists, and where it stays | Open (recommend: as described) |
| B9 | Bin Files when nothing needs to move | Open (recommend: off; in-place items just count as binned) |
| B10 | Re-sort includes binned items (spec) or leaves them out (2b Task 9) | **Approved Nov 4**: include them, as the spec says |
| Q2 | The removed-file record | Approved Oct 31 in principle; details open (recommend: as described) |
| P8 | Media another project already holds | **Decided Nov 4** (owner's own design): notice with Cancel / Copy media files; copies go to `<project>/Copied from <other>/` |
| B11 | MediaStore after a move, and what Gallery and both pickers show | Open (recommend: scan both paths; keep the file's date) |
| B12 | Source folders are never removed; only category folders the app made | Open (recommend: yes) |

## B1. Copy, check, then delete, or rename when the file stays on the same storage?

The brief's binding rule: "Copy, verify size and SHA-256, update `currentPath`
in the database, then delete the original." The spec's "Left to the builder"
allows an atomic rename, checked afterwards, for moves within one storage
volume (internal storage to internal storage, which is almost every move).

- a. **Always copy, check, then delete.** Every file is read twice and written
  once. A 4 GB clip needs 4 GB free while it moves. About 1–2 minutes per
  10 GB on the S26 (to measure on the phone).
- b. Rename within a volume, copy across volumes (SD card). Instant, needs no
  free space, and the file never exists twice. The check after a rename hashes
  the destination against the hash taken when the item was added, which is
  stale if the file was edited in Gallery since.

**Recommend (a) for Plan 3**, because it is the brief's rule and the one the
owner asked for. If moving a large video library turns out slow on the phone,
(b) can come later behind the same `FileMover` interface without changing
the plan or resume logic.

**How the copy works** (so the owner can check it):

1. Copy the source to `<project>/.binning/moving/<step id>.<ext>`, hashing
   the source as it is read. `.binning` holds `.nomedia`, so Gallery never
   sees the half-written file. The file's last-modified time is copied too.
2. Check: the copy's size equals the source's, and the copy, read back from
   storage, has the same SHA-256 as the source.
3. Rename the checked copy into `<project>/<Category>/<name>.<ext>`. That is a
   rename inside one folder tree on one volume, so it is atomic. If the name
   was taken since planning, the next free " 2", " 3" is used instead. Never
   overwrite.
4. In one database transaction: the item's `currentPath` and status Binned,
   and the step `verified`.
5. Delete the original; the step is `done`. Tell MediaStore (B11).

At every moment the file exists at its source, or checked at its destination,
or both.

## Q11. Can the user leave the screen while files move? *(deferred from 2b)*

Spec: Bin Files and Save "also run in the foreground service". 2b state 6
says "Other edits wait until this finishes. You can still browse and search."

- a. **Yes.** The move runs in the foreground service, like analysis. Leaving
  Project Media, going back to Analyze or leaving the app doesn't stop it.
  The notification shows "Binning Hawaii Photos · 12 of 248" (or "Saving ·
  2 of 3 files"). Coming back shows the progress card. While files move,
  these are off, each with its reason: Add media, switching or removing the
  open project (Current projects), Resume analysis, Re-sort, Categorize now,
  and every Save.
- b. No. Project Media holds the user on the progress card until the move
  ends; Back asks "Files are still moving".

**Recommend (a).** The engine is resumable either way, a 248-file move can
take minutes, and holding the screen gains no safety. State 2 shows the
notification.

## B2. Stop and Pause during Bin Files

The spec: "Pause, Stop and analysis controls are disabled while it runs."

- a. **"Stop after this file"** on the progress card and in the notification.
  The current file finishes (or, if it hasn't been copied yet, isn't
  started), the run ends as stopped, and the report says how many moved and
  how many are still waiting. Pressing Bin Files again plans the rest. No
  Pause: Stop and Bin Files again is the same thing.
- b. No Stop at all, as the spec says. A long move can only end or crash.

**Recommend (a).** The engine has to stop cleanly between files anyway (the
system can end the service), so a button costs little. Analysis Pause and
Stop on the Analyze screen stay off while files move, as the spec says.

## B3. Resuming after a crash, force-stop or reboot

**Status: approved Nov 4, 2026** (recommended answer (a) accepted).

The plan and each step's state are in `move_run` / `move_step`, so a run
the process didn't finish is found the next time the project opens.

- a. **Resume on its own** when the project opens (app launch, or opening the
  project): a card says "Picking up where Bin Files stopped · 13 of 248" and
  the run continues in the service. The step that was in flight is finished
  first (state 5). A step interrupted twice is marked failed and the run
  stops on it (B4), so a file that crashes the app can't loop.
- b. Ask: "Bin Files didn't finish. Continue / Later". Later leaves the
  interrupted file existing twice (source and checked copy) until the user
  continues, and every other job waits.

**Recommend (a).** Finishing is the safe direction, and the user already
pressed Bin Files. No boot receiver: after a reboot nothing happens until the
app opens.

## B4. A copy that doesn't match its original

The per-file check (B1 step 2) fails: sizes differ, or the hashes do.

- a. **Delete the bad copy and copy once more.** If the second copy fails too,
  stop the run: the item stays where it was, untouched. The report (state 4)
  says "Bin Files stopped at <name>: its copy didn't match the original.
  Nothing was lost. 37 files moved, 211 still where they were." with
  **Try again** and **Close**. Try again plans afresh.
- b. Skip that file and carry on with the rest.

**Recommend (a).** Two bad copies in a row mean the storage is failing or
full, and every later copy would be at risk too. The item is not marked
failed: nothing is wrong with it.

A source that has vanished since planning (deleted in Gallery mid-run) is
different: that item becomes Missing, the run carries on, and the report
lists it.

## B5. The folder check: before and after, and what blocks

**Status: approved Nov 4, 2026** (block, with Add to project and Leave them).

Spec step 3: after the moves, the files across the category folders must
match the binned items, and on any mismatch stop and show which are missing
or extra.

Proposed:

- The check runs **before** planning as well as after the moves, so a problem
  that was already there is found before anything moves.
- **Missing** (a binned item's file is gone, say deleted in Gallery): the item
  becomes Missing and is listed in the report. It never blocks, like any
  missing item (2b Q7, Q18).
- **Extra** (a file in a category folder that no item of the project points
  to and that wasn't removed from the project, say copied in with a file
  manager): Bin Files stops before moving anything and shows the files
  (state 5) with two ways out:
  - **Add to project**: registers them like Add a folder; they are analyzed
    and sorted, then binned.
  - **Leave them**: recorded like removed files (Q2), so the check skips them
    from now on.
- Files that belong to an item of the project but aren't binned (a binned
  item a Re-sort moved to another category, a failed item added from inside a
  category folder) are expected, not extra.
- After the moves, a mismatch can only come from something changing files
  while the run went (the engine itself never leaves one). The run is already
  finished; it is reported the same way.

**Recommend as described.** The alternative, stopping on missing files too,
would block Bin Files on every file the user deletes in Gallery.

## B6. Storage full mid-move

With B1 (a), each file needs its own size free while it moves (the original
is deleted before the next one starts).

- **Before each file:** if free space is less than the file's size plus
  200 MB, the run stops before copying it: "Storage is full. 37 files moved;
  211 still where they were. Free up 3.1 GB and press Bin Files again." The
  file's size is the figure shown.
- **During a copy** (another app filled the storage): the half copy is
  deleted, the step goes back to planned, and the run stops with the same
  message.
- Before the run starts, the confirm sheet (B7) warns if free space is less
  than the largest file plus 200 MB.

**Recommend as described.** The margin keeps room for the database journal
and for the phone itself.

## B7. Confirm before Bin Files?

- a. **A confirm sheet** (state 1): "Bin 248 files", into how many folders,
  where they come from ("from Camera 210 · Download 38"), where they go
  (`Agentic Binning/Hawaii Photos`), what stays (1 failed, 2 missing), and one
  line: "No undo. Each file is copied, checked, then the original is
  deleted. In Gallery they move to albums named after their categories."
  Buttons: Cancel, **Bin 248 files**.
- b. No confirm: Bin Files starts at once.

**Recommend (a).** It is the one irreversible action in the app, files leave
DCIM/Camera, and there is no undo (Non-goals). Save moves don't get a confirm:
their sheets already say "Save and move 3 files" (2b states 3b, 5a, 5b).

## B8. What the report lists

Shown when a run ends (state 3), and again when the user comes back if they
left (Q11), until they close it.

- Headline: "248 files binned into 14 category folders."
- Moved now; already in place; names that got a number ("3 names were taken
  and got a number, e.g. Beach Sunset 2.jpg").
- **Left where they are**, each with its name and why: Failed (with its
  reason), Missing, In another project (P8). Tapping one opens its sheet (the
  2b Failed sheet).
- Empty category folders removed.
- The check: "Checked 248 files in 14 folders. Nothing missing or extra."
- Stopped runs (B2, B4, B6) say why and how many are still to move.

The last run's counts and message are kept in `move_run`, so a report can be
shown after a restart. Steps are kept as history (B12 needs them).

**Recommend as described.**

## B9. Bin Files when nothing needs to move

- Bin Files stays off when every sorted item is in place, with "All files
  are binned" on tap (2b Task 3). No "check only" run.
- If Bin Files is on but the plan finds nothing to move (every item counted
  ready was already in place, for example media added from inside a category
  folder), those items are marked binned, the check runs, and the report says
  "Nothing to move. 12 files were already in place."

**Recommend as described.**

## B10. Does Re-sort reach binned items?

**Status: approved Nov 4, 2026: (a), include them**, as the spec says. Plan 3 changes `SortDao.prepareResort` (and 2b Task 9's note).

The spec: "Binned files whose category or name changed move at the next Bin
Files, and the status line shows how many are waiting." So a Re-sort may
change binned items. 2b Task 9 left Binned items out of Re-sort ("Failed,
Missing and (in Plan 3) Binned items are left out"), and `SortDao.prepareResort`
does that today.

- a. **Include unlocked binned items, as the spec says.** Re-sort may give them
  another category or name; their files stay put until the next Bin Files,
  and the status line counts them as ready to bin.
- b. Leave binned items out of Re-sort: once binned, only the user's own
  edits (Save) change an item.

**Recommend (a)**, the spec's wording. (b) is safer for a library the user
has tidied, but Keep my edits already protects what they set. Either way
nothing moves until Bin Files.

## Q2. The removed-file record *(approved Oct 31 in principle)*

Approved: a removed binned item's file stays where it is, and its path is
remembered so the folder check skips it. The details:

- A `removed_file(path, removedAt)` table (schema v4).
- `Remove from project` adds a row for each removed item whose file is in a
  category folder, in the same transaction that deletes the item.
- B5's **Leave them** adds rows too.
- A row is dropped when that file is added to the project again, or is gone
  from disk (checked at each Bin Files).
- A later binned file never takes a removed file's name: the planner sees it
  on disk and adds " 2".
- When its category is renamed or merged, the removed file stays in the old
  folder, so that folder isn't empty and is kept (B12). The report says
  "Kept Turtle Beach: it holds 1 file removed from the project."

**Recommend as described.**

## P8. Media another project already holds *(2b P8 revisit)*

**Status: decided Nov 4, 2026, with the owner's own design** (it replaces the
recommendation below, kept for the record).

Today registration skips only the folders of projects on the Current projects
list, and refuses a file a listed project holds by hash or path ("in another
project (<name>)"). A project taken off the list (×) keeps its folder,
database and binned files, so another project could add those files and
leave one file in two projects.

**Decided:**

- **Which files:** any file another project holds: a file inside another
  project's folder, listed or not (a folder holding `.binning/project.db`), and
  a file elsewhere (say DCIM/Camera) that a listed project holds by hash or
  path.
- **Both add flows** (Add a folder and Pick photos and videos) show **one
  notice** naming the other project or projects and how many files, with
  **Cancel** and **Copy media files** (state 7).
  - Cancel adds nothing of the files another project holds; the rest of the
    batch is still added.
  - Copy media files copies each of those files into
    `<project>/Copied from <other project>/`, with the copy's progress in the
    notice. Each copy is checked (size and SHA-256) like a Bin Files copy. The
    other project's file is never moved, renamed or deleted.
- Once copied, the copy is an ordinary item of this project (its
  `originalPath` is the copy). It stays in `Copied from <other>/` until Bin
  Files moves it into its category folder like any other file.
- A `Copied from <other>/` folder is one the app made, so it is removed once
  Bin Files empties it, if it is truly empty (B12).

**Follow-on rulings** (cheap to change, in the ledger):

- A file in two projects can still happen when one of them was off the list
  while the other added the same file from outside both folders (its database
  can't be found from the file). At Bin Files, an item whose path another
  listed project also holds is **copied** into its category folder instead of
  moved, and its original is left where it is, as the owner asked for shared
  files. The report lists it as "Copied, also in <project>".
- The duplicate check inside this project is unchanged: a copy has the same
  hash as the other project's file, but no file of this project.

*Recommended before the decision:* skip every project folder found on disk,
and leave items shared with another listed project out of Bin Files.

## B11. MediaStore after a move, and what Gallery and the pickers show

Spec: "After a move, notify MediaStore (scan the new path, remove the old
one)." Proposed: after each step, `MediaScannerConnection.scanFile` on both
paths (scanning a path that no longer exists removes its row). Writes through
the FUSE filesystem already update MediaStore on Android 11 and later; the
scan makes sure. The copy keeps the original's last-modified time.

What the owner will see after a file moves out of a folder like DCIM/Camera
(to confirm on the phone; the plan's phone end-to-end task checks each):

- **Samsung Gallery, Pictures tab:** the photo stays at the same place in the
  timeline: its date taken comes from EXIF or the video's metadata, and
  last-modified is kept for files without one.
- **Samsung Gallery, Albums tab:** it leaves the Camera album. Each category
  folder becomes an album named after the category ("Beach Scenes"). Two
  projects with a category of the same name show two albums with that name.
- **Lost in Gallery:** the moved file is a new MediaStore entry, so anything
  Gallery keeps against the old entry is lost: Favourites, Gallery's own albums
  and stories, and "Revert" to the original for photos edited in Gallery.
  The original is deleted outright, not sent to Gallery's Recycle bin.
- **Android photo picker** (the default "Pick photos and videos"): the photo
  is still under Photos at the same date. It leaves the picker's Camera album.
  Which other albums list category folders differs by Android version; to
  check on the phone.
- **Samsung Gallery's picker:** the same as Gallery: category folders show as
  albums.
- **Picking a binned file again:** both pickers return the moved file's new
  entry, and `MediaUris` resolves it to the real path inside the project
  folder. Adding it to the same project is "already in this project"; to
  another project, "in another project's folder (<name>)". The app never
  stores a picker URI or reads a name or location from one; items keep their
  `originalPath` and `currentPath`.
- **Cloud backup** (Samsung Cloud, OneDrive, Google Photos): files outside
  DCIM/Camera may not be backed up unless their folder is turned on. To check;
  the confirm sheet could mention it once confirmed.
- **Uninstalling the app** leaves moved files in place (they are shared
  storage). To confirm, since `connectedDebugAndroidTest` uninstalls.

**Recommend: scan both paths after each step and keep last-modified.** Nothing
in the app depends on the rest; it is what the owner should expect.

## B12. Which folders may be removed

Spec: "A category folder left empty is removed only if it is truly empty."

- Only a folder the app made, directly inside the project folder: one that a
  finished step put a file in (from `move_step`), or a `Copied from <other>/`
  folder (P8).
- Removed with a plain directory delete, which fails if anything is inside,
  including hidden files. A folder that isn't empty stays.
- Source folders (DCIM/Camera, Download, a user's own folder inside the
  project) are never removed, even when Bin Files empties them.

**Recommend as described.**

## Rulings I made without asking (cheap to change)

These are in the handoff ledger too.

- Status Binned stays in step with the file: the engine sets it when a file
  reaches its target; a change that alters a binned item's target without
  moving it (Re-sort, B10) sets it back to Sorted. "Ready to bin" and the
  planner decide by path (plan-2-notes).
- The target folder is always the category's current name made safe
  (`MovePlanner.folderFor`); `category.folderName` records the folder that
  exists on disk, so the Edit sheet can say "renames the folder".
- A category rename at Save moves each binned file into the new folder as one
  step per file, then removes the empty old folder (2b "Left for Plan 3").
- If a file was edited since it was added, its copy is checked against the
  file as read now, and the item's stored hash is updated.
- A Save that touches binned files writes its edit and its move plan in one
  transaction, so a crash between the two can't leave a binned item's name
  out of step with its file.
