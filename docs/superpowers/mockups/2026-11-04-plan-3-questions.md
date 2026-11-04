# Plan 3: open design questions for binning

Nov 4, 2026 · goes with `2026-11-04-plan-3-binning.html`

Answered on Nov 4 by the owner: B1, B2, B3, B4, B5, B6, B9, B10, B12, Q2, Q11 and P8. **Open: B7 (confirm sheet), B8 (report) and B11 (MediaStore and what Gallery shows)**, to settle when the owner reviews the mockups.

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
| B1 | Rename on the same storage; copy, check, delete across storage | **Decided Nov 4**: rename (b), the owner's choice over the brief's copy rule |
| Q11 | The user may leave the screen while files move | **Approved Nov 4** (yes) |
| B2 | Pause and Stop during Bin Files, each after the current file | **Decided Nov 4**: both Pause and Stop (owner) |
| B3 | A crashed run resumes on its own when the project opens | **Approved Nov 4** (as recommended) |
| B4 | A copy that doesn't match its original | **Decided Nov 4**: retry once, then skip that file and carry on |
| B5 | Folder check before and after; extra files block until added or ignored | **Approved Nov 4** (as recommended) |
| B6 | Storage full: check before each copy, stop cleanly | **Approved Nov 4** (as described) |
| B7 | A confirm sheet before Bin Files | Open (recommend: yes) |
| B8 | What the report lists, and where it stays | Open (recommend: as described) |
| B9 | Bin Files when nothing needs to move | **Approved Nov 4** (as described) |
| B10 | Re-sort includes binned items (spec) or leaves them out (2b Task 9) | **Approved Nov 4**: include them, as the spec says |
| Q2 | The removed-file record | Approved Oct 31; **details approved Nov 4** |
| P8 | Media another project already holds | **Decided Nov 4** (owner's own design): notice with Cancel / Copy media files; copies go to `<project>/Copied from <other>/` |
| B11 | MediaStore after a move, and what Gallery and both pickers show | Open (recommend: scan both paths; keep the file's date) |
| B12 | Source folders are never removed; only empty folders the app made | **Approved Nov 4** (as described) |

## B1. How a file moves: rename on the same storage, copy across

**Status: decided Nov 4, 2026: (b), rename within one storage volume.** The
owner chose this over the brief's "copy, verify, update, delete" rule, which
it replaces for moves within a volume. Copy, check, delete stays for moves
between volumes.

Asked: "does the rename also change its file location to the new location?"
**Yes.** On Android (Linux), renaming a file to a path in another folder on the
same storage moves it there in one step: the folder and the name change
together. The file's data isn't read or rewritten; only its directory entry
changes. Because it is a single atomic step, the file is always at its old
path or its new one, never both and never neither, and no free space is
needed.

- a. *(not chosen)* Always copy, check, then delete.
- b. **Rename within a volume, copy across volumes** (internal storage to an SD
  card or the other way).

**How a rename move works:**

1. Before: the source exists; note its size. The target name is free (a name
   taken since planning gets the next " 2", " 3"). Never overwrite.
2. Rename the source to `<project>/<Category>/<name>.<ext>`.
3. Check: the file is at the target with the same size, and gone from the
   source. Hash it and store the hash on the item (it changes if the file was
   edited in Gallery since it was added; that is not an error).
4. In one database transaction: the item's `currentPath` and status Binned,
   and the step `done`. Tell MediaStore (B11).

A crash between 2 and 4 is safe: on resume the file is found at the target,
with the right size, and the database is brought up to date.

**How a copy move works** (across volumes):

1. Copy the source to `<project>/.binning/moving/<step id>.<ext>`, hashing
   the source as it is read. `.binning` holds `.nomedia`, so Gallery never
   sees the half-written file. The file's last-modified time is copied too.
2. Check: the copy's size equals the source's, and the copy, read back from
   storage, has the same SHA-256 as the source.
3. Rename the checked copy into `<project>/<Category>/<name>.<ext>` (same
   volume, so atomic). Never overwrite.
4. In one database transaction: the item's `currentPath` and status Binned,
   and the step `verified`.
5. Delete the original; the step is `done`. Tell MediaStore (B11).

At every moment the file exists at its source, at its destination, or both.

## Q11. Can the user leave the screen while files move? *(deferred from 2b)*

**Status: approved Nov 4, 2026** (recommended answer (a) accepted).

- a. **Yes.** The move runs in the foreground service, like analysis. Leaving
  Project Media, going back to Analyze or leaving the app doesn't stop it.
  The notification shows "Binning Hawaii Photos · 12 of 248" (or "Saving ·
  2 of 3 files") with Pause and Stop (B2). Coming back shows the progress
  card. While files move, these are off, each with its reason: Add media,
  switching or removing the open project (Current projects), Resume analysis,
  Re-sort, Categorize now, and every Save.
- b. *(not chosen)* Project Media holds the user on the progress card.

## B2. Pause and Stop during Bin Files

**Status: decided Nov 4, 2026, by the owner:** "just like when copying on any
other system or OS, a stop and pause should be available. To avoid corrupting
anything it should finish the file it is on, and then stop or pause before it
moves the next file." This replaces the spec's "Pause, Stop and analysis
controls are disabled while it runs" for Bin Files and Save moves.

- **Pause** (progress card and notification): the current file finishes, then
  the run holds before the next one. The card says "Paused · 37 of 248" with
  **Resume** and **Stop**. While paused the run still owns the project: other
  jobs and edits stay off (Q11), so nothing can change the plan under it. The
  wake lock is released while paused, as for analysis.
- **Stop**: the current file finishes, the run ends as stopped, and the report
  (state 4) says how many moved and how many are still where they were.
  Pressing Bin Files again plans the rest.
- A paused run survives the app being closed like any run: it resumes on its
  own when the project opens (B3), paused again if it was paused.
- Analysis Pause and Stop on the Analyze screen don't apply to moves; the
  Analyze screen's bottom bar shows the move's state instead.

## B3. Resuming after a crash, force-stop or reboot

**Status: approved Nov 4, 2026** (recommended answer (a) accepted).

The plan and each step's state are in `move_run` / `move_step`, so a run
the process didn't finish is found the next time the project opens.

- a. **Resume on its own** when the project opens (app launch, or opening the
  project): a card says "Picking up where Bin Files stopped · 13 of 248" and
  the run continues in the service. The step that was in flight is finished
  first (state 6). A step interrupted twice is skipped (B4), so a file that
  crashes the app can't loop.
- b. Ask: "Bin Files didn't finish. Continue / Later". Later leaves the
  run half done until the user continues, and every other job waits.

**Recommend (a).** Finishing is the safe direction, and the user already
pressed Bin Files. No boot receiver: after a reboot nothing happens until the
app opens.

## B4. A copy that doesn't match its original

**Status: decided Nov 4, 2026: (b), retry once, then skip that file.**

Only copies across volumes (B1) can have a bad copy. The per-file check fails:
sizes differ, or the hashes do.

- a. *(not chosen)* Retry once, then stop the run.
- b. **Delete the bad copy and copy once more. If the second copy fails too,
  skip that file:** delete the copy, leave the item where it was, untouched
  and still ready to bin, and carry on with the next file. The report lists
  it under "Couldn't move" with "its copy didn't match the original twice;
  nothing was lost". The next Bin Files tries it again. The item is not
  marked failed: nothing is wrong with it.

A rename whose check fails (the file isn't at the target with the same size)
can't be undone by skipping; the file is wherever the system put it. The run
stops and the report says where the file is. This should never happen.

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

**Status: approved Nov 4, 2026** (as described).

Renames (B1) need no space. Only copies across volumes do: each file needs its
own size free on the project's storage while it moves (the original is
deleted before the next one starts).

- **Before each copy:** if free space is less than the file's size plus
  200 MB, the run stops before copying it: "Storage is full. 37 files moved;
  211 still where they were. Free up 3.1 GB and press Bin Files again."
- **During a copy** (another app filled the storage): the half copy is
  deleted, the step goes back to planned, and the run stops with the same
  message.
- Before the run starts, the confirm sheet (B7) warns if a copy will need more
  than the free space.
- Every run also stops if free space falls under 50 MB, so the database
  journal always has room.

**Recommend as described.**

## B7. Confirm before Bin Files?

**Status: approved Nov 3, 2026, local time: (a), a confirm sheet** (state 1).

- a. **A confirm sheet** (state 1): "Bin 248 files", into how many folders,
  where they come from ("from Camera 210 · Download 38"), where they go
  (`Agentic Binning/Hawaii Photos`), what stays (1 failed, 2 missing), and one
  line: "No undo. Each file moves into its category folder and is checked;
  nothing is overwritten. In Gallery they move to albums named after their
  categories." Files on an SD card add "copied, checked, then deleted from
  the card", and the free space a copy needs (B6).
  Buttons: Cancel, **Bin 248 files**.
- b. No confirm: Bin Files starts at once.

**Recommend (a).** It is the one irreversible action in the app, files leave
DCIM/Camera, and there is no undo (Non-goals). Save moves don't get a confirm:
their sheets already say "Save and move 3 files" (2b states 3b, 5a, 5b).

## B8. What the report lists

**Status: approved Nov 3, 2026, local time, as drawn** (states 3 and 4).

Shown when a run ends (state 3), and again when the user comes back if they
left (Q11), until they close it.

- Headline: "248 files binned into 14 category folders."
- Moved now; already in place; names that got a number ("3 names were taken
  and got a number, e.g. Beach Sunset 2.jpg").
- **Left where they are**, each with its name and why: Failed (with its
  reason), Missing, In another project (P8). Tapping one opens its sheet (the
  2b Failed sheet).
- **Couldn't move** (B4): files whose copy didn't match twice; still where
  they were, tried again by the next Bin Files.
- Empty category folders removed.
- The check: "Checked 248 files in 14 folders. Nothing missing or extra."
- Stopped runs (B2, B6) say why and how many are still to move.

The last run's counts and message are kept in `move_run`, so a report can be
shown after a restart. Steps are kept as history (B12 needs them).

**Recommend as described.**

## B9. Bin Files when nothing needs to move

**Status: approved Nov 4, 2026** (as described).

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

**Status: details approved Nov 4, 2026** (as described).

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
paths (scanning a path that no longer exists removes its row). Renames and
writes through the FUSE filesystem already update MediaStore on Android 11 and
later; the scan makes sure. A rename keeps the file's last-modified time by
itself; a copy sets it from the original.

What the owner will see after a file moves out of a folder like DCIM/Camera
(to confirm on the phone; the plan's phone end-to-end task checks each):

- **Samsung Gallery, Pictures tab:** the photo stays at the same place in the
  timeline: its date taken comes from EXIF or the video's metadata, and
  last-modified is kept for files without one.
- **Samsung Gallery, Albums tab:** it leaves the Camera album. Each category
  folder becomes an album named after the category ("Beach Scenes"). Two
  projects with a category of the same name show two albums with that name.
- **Favourites and Gallery's own data:** a rename through FUSE (B1, almost
  every move) should update the file's existing MediaStore entry in place, so
  its id stays and Gallery may keep Favourites, its own albums and stories,
  and "Revert" for photos edited in Gallery. To confirm on the phone; it is
  the most likely difference the owner will notice. A copy (SD card) is a new
  entry, so those are lost for copied files; the original is deleted
  outright, not sent to Gallery's Recycle bin.
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

**Status: approved Nov 4, 2026** (as described).

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
