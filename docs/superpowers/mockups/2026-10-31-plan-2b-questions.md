# Plan 2b: open design questions for the Project Media screen

Oct 31, 2026 · goes with `2026-10-31-plan-2b-project-media.html`

These are the decisions the spec leaves open or marks *(my call)*, plus the
ones the mockups had to make to draw the screen. Most important first. Each
has a recommended answer; the mockups show that answer unless the question
says otherwise. "State N" refers to the numbered states in the HTML file.

Every *(my call)* in the spec's Project Media section is here: secondary
filters (Q3), Keep my edits ticked by default (Q4) and Remove from project
never deletes the file (Q1). The lock on user edits (Q5) is a *(my call)* from
"3. Categorize" that the Project Media markers depend on.

## 1. Remove from project only forgets the item, never deletes the file *(my call)*

Spec: "Remove from project deletes only the item's database row and never the
file." The mockups follow it: the dialog says where the files stay (state 4b),
and the detail sheet and the Failed sheet offer the same Remove.

**Recommend: yes, as written.** The app's one unforgivable failure is a lost
file, and a Remove that deletes would be the easiest way to cause one.
Deleting files can stay a gallery job.

## 2. What happens to a removed item whose file is already binned?

Its file sits in a category folder, say `Turtle Beach/Sea Turtles Resting
Sand.jpg`. The Bin Files count check requires "no unexpected files" in the
category folders, so the next Bin Files would stop on that file.

Options:

- a. Leave the file in place and remember its path as removed, so the count
  check skips it. The dialog says the file stays in its folder (state 4b).
- b. Move the file back to its original path before forgetting the item.
- c. Don't allow Remove for binned items.

**Recommend (a).** It moves nothing, keeps the rule that only Bin Files and Save
move files, and the user can see where the file is. (b) is a hidden file move;
(c) leaves no way out.

## 3. Secondary filters: photo/video, shot type and date *(my call)*

Spec: these replace the earlier Subject/Setting/Shot/Date selector, because
categories are now the main way to group. The mockups show them as a row of
three dropdown chips under the categories, always visible (state 1). A set
filter turns blue and shows its value; each opens a small sheet with counts.

**Recommend: yes, always visible.** Hiding them behind a filter button saves
one row but makes "videos only" two taps away. If the screen feels crowded on
the phone, move them behind a button later.

## 4. Re-sort keeps your edits by default *(my call)*

Spec: "Categories and items you set yourself are kept unless you untick Keep
my edits, which is ticked by default." The Re-sort sheet (state 7) shows the
box ticked, says how many categories and items it protects, and warns when
unticked that they may be replaced and stop being locked.

**Recommend: ticked by default.** A re-sort that silently undoes hand edits is
the surprise users won't forgive. Unticking is the deliberate "start over".

## 5. What you set is locked, and you can unlock it *(my call, from "3. Categorize")*

Spec: sorting never renames, merges or removes a category you created or
renamed, and never reassigns an item whose category you chose. The mockups:

- show a **Set by you** lock pill on the name and category in the detail sheet
  (state 3), and a "Set by you when you save" pill while an edit is pending;
- add an **Unlock** link next to the lock, which hands the value back to
  sorting on Save;
- put a small lock on your categories in the chips row, the picker and the
  Edit list;
- treat a bulk move, a rename and a merge as setting the value (so they lock).

**Recommend: as mocked, Unlock included.** Without Unlock, the only way to
release one lock is a Re-sort with Keep my edits unticked, which releases all
of them.

## 6. Save per sheet, not a screen-wide "unsaved changes" bar

Spec: "Nothing changes until you press Save." The mockups put the Save in each
editing surface: the detail sheet, the Move to category sheet ("Save · move
4"), the Edit categories sheet ("Save 2 changes") and the Failed sheet's
by-hand form. Cancel or closing a sheet drops its edits.

**Recommend: per sheet.** Each Save has a clear scope, and its footer can say
which files move now. A global pending bar would let edits pile up across
sheets and make one Save move many unrelated files.

## 7. Should items waiting to be categorized block Bin Files?

Spec: Bin Files is enabled only when no item is "waiting to be categorized",
and such items are retried on the next sorting run. When no new media arrives,
there is no next run, so Bin Files stays off. The mockups (state 1):

- a **Waiting** chip at the front of the categories row, with a count;
- a "Waiting" badge on those tiles;
- "34 files ready to bin · 3 waiting to be categorized" with a **Categorize
  now** link in the status line (the spec's "Categorize now" action);
- the detail sheet lets you choose a category by hand, and bulk Move works on
  them too.

**Recommend: keep blocking**, with these three ways out. The alternative, to
bin around them like Failed items, would leave files behind without a
reason the user chose.

## 8. Where Re-sort, Edit and Select live

Mocked: **Re-sort** and **Edit** sit on the categories header row ("CATEGORIES
· 12 · Re-sort · Edit"), because both act on the categories. **Select** sits on
the result line above the grid; long-press on a tile also enters select mode.
Categorize now appears only in the status line, and only when items wait.

**Recommend: as mocked.** No overflow menu, so every action is visible. If
Re-sort feels too prominent for something run rarely, move it into an
overflow menu on the header.

## 9. Waiting and Failed as chips in the categories row

Mocked: amber **Waiting** and **Failed** chips right after All, shown only
when there are such items. The spec only asks for a badge on Failed items.

**Recommend: yes.** They are the two groups that need the user, and a chip is
the quickest way to find a few items among hundreds.

## 10. Merge: two at a time, survivor keeps one of the two names

Spec: "rename a category or merge two." Mocked (state 5b): tick two, then
choose which name survives. The choice says what happens to files: keeping a
name whose category has no folder moves the other's binned files now; keeping
the binned one moves nothing now. To get a third name, merge and then rename.

**Recommend: as mocked.** Merging three or more at once can come later if the
model's categories turn out to need it.

## 11. Renaming or merging a binned category moves files at Save

Spec: renaming a category whose folder exists renames the folder; merging
moves the files into the surviving folder; both happen on Save, with progress,
while other edits wait. Mocked (state 6): a progress card above the bottom
bar with one row per file (Copying, Verifying, Done), then "Remove empty
folder" and "Count check". Edit, Select and Re-sort say edits wait; detail
sheets open but their Save reads "Waits for the current save"; search and
filters keep working.

Question: should the user be able to leave the screen while a Save runs?
**Recommend: yes.** It runs in the foreground service like Bin Files, and the
Analyze screen's Project Media pill shows "Saving…" until it finishes.

## 12. Re-sort while analysis is running

The "one model loaded" rule means Re-sort and Categorize now can't run while
the vision model is busy. **Recommend:** disable both while analysis runs and
say why ("Re-sort starts when analysis is paused or done"), rather than queue
them silently. Not drawn in the mockups.

## 13. The Re-sort cost note

Mocked: "About $0.05. Sends the text of 43 items … roughly 12K tokens in and
3K out, at $2 and $10 per million tokens on your API key." That is the
Sonnet 5.5 price. The estimate is roughly 250 tokens per item plus the
category list and prompt.

**Recommend:** estimate locally from the item text (no extra API call), round
to "about $X", and keep the per-million prices on screen so the figure can be
checked. Show it only for Claude.

## 14. Qwen3-8B in the Re-sort picker

Mocked as a third option with a Download pill; picking it changes the button
to "Download and re-sort". The spec says to leave Qwen3-8B out of Settings if
neither the `qairt` bundle nor a GGUF works with geniex 0.10.0.

**Recommend:** the Re-sort picker lists exactly what Settings lists.

## 15. Search behaviour

Mocked (state 9): categories with no matches drop out of the row during a
search; tiles found through something other than the name show "Matched in
description / tags / actions / category". The result line reads "5 results
for “turtle”".

**Recommend: as mocked.** The match line explains why a tile that doesn't say
"turtle" is there (the snorkel photo matched in its description).

## 16. Tile markers

Mocked: a folder tick on binned tiles, a "Waiting" or amber "Failed" badge,
and a duration badge on videos. Locks are not shown on tiles; they appear in
the detail sheet and on category chips.

**Recommend: as mocked.** A lock on every edited tile would add noise for
little gain.

## 17. Grid order

Mocked: by capture time, oldest first, so a trip reads in order. Category
filters keep that order.

**Recommend: oldest first** for now. A sort toggle can come with the future
"sort method" setting.

## 18. Status line wording when several things are true

Mocked: ready count first, then waiting count, with the Categorize now link;
"1 failed" appears only when nothing is waiting. Examples in the "Status line
and Bin Files" strip: "Analyzing 12 left", "Categorizing…", "34 files ready to
bin · 3 waiting to be categorized", "37 files ready to bin · 1 failed",
"Saving · moving 2 of 3 files", "All files binned".

**Recommend: as mocked.** Two facts at most, so it fits on two lines.

## 19. Failed item: retry and set by hand

Mocked (state 8): Retry analysis, Set name and category by hand, Remove from
project. Setting by hand gives the clip a name and category with both locks
on and no description, so search finds it only by name and category.

Question from the Plan 1 notes: should a retry reset the attempts counter?
**Recommend: yes**, reset on a user retry, so a clip that failed twice to an
interruption can be tried again.
