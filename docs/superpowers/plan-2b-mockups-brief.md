# Plan 2b mockups: brief for a cloud session

Start a session with: "Follow `docs/superpowers/plan-2b-mockups-brief.md`."

## The task

Make clickable HTML mockups of the **Project Media screen**, for the user to
react to before the Plan 2b implementation plan is written. This is design
only: no Kotlin, no implementation plan, no app code, and no changes to
existing files.

## Why mockups first

The user wants to see each screen before it is built. Plan 2 is split in two:

- **2a, sorting.** Done on branch `plan-2a-sorting`, which is not on GitHub
  yet. It is being tested on the phone.
- **2b, the Project Media screen, editing, and Re-sort.** Its plan is written
  only after these mockups are approved.

## Read first

1. `docs/superpowers/specs/2026-10-28-agentic-binning-design.md`. This is the
   binding authority. Read "Concepts", "Screens" (especially "Project Media
   screen"), "3. Categorize", "4. Bin Files", and "UI build notes". Everything
   the mockups show must agree with it. Where the spec marks something
   *(my call)*, show it that way and list it as a question.
2. `docs/superpowers/plan-2-notes.md`: decisions carried into Plan 2.
3. `CLAUDE.md`: what the app is.

## What Plan 2a already provides

The 2a code is not on GitHub, so here are the facts the mockups can rely on:

- **Items** have:
  - `status`: Pending, Analyzing, Analyzed, Sorted, Binned, Failed, or
    Missing.
  - `categoryId`.
  - `friendlyName`, the short name the sorting model gives an item, for
    example "Gecko on Wooden Wall".
  - `nameSetByUser` and `categorySetByUser`. These are locks: once the user
    sets a value, sorting never changes it.
  - From analysis: a description, actions (a timeline, for videos), tags, a
    suggested name, and a shot type.
- **Categories** have:
  - a name and a description;
  - `createdBy`, either `model` or `user`. User categories are never
    renamed by sorting;
  - `folderName`, which is set once the category's files have been binned.
- **Sorting** runs automatically after each analysis batch. It uses Claude
  (cloud; sends text only, never photos) or LFM3-1.7B (on the phone),
  chosen in Settings.
  - Items it couldn't place stay "waiting to be categorized" and are retried
    on the next run.
  - A general catch-all category (for example "Misc") is allowed only for a
    handful of items, at most max(5, 5% of the project).
- **Search** is SQLite FTS5 over descriptions, actions, tags, friendly
  names, and category names.

## Sample data

Use realistic data from a Hawaii trip. The real Hawaii Photos project sorted
into these categories:

- Forest Nature (6)
- Aerial View (5)
- Sunset Over Sea (4)
- Lunch Outdoors (4)
- Fire Performance (4)
- Green Lizard (4)
- Windsurfers on the Sea (3)
- Turtle Beach (3)
- Beach Scenes (3)
- Mama's Fish House (2)
- Breakfast (1)
- Wedding Events (1)

Example friendly names:

- "Gecko on Wooden Wall"
- "Fire Dancers Night"
- "Sea Turtles Resting Sand"
- "Serene Bay Sunset"
- "Airplane Window Landscape Sky"

Include:

- a few videos, shown with a duration badge;
- one Failed item;
- a few items waiting to be categorized;
- a few items already binned.

Use plain colored or gradient placeholders for the media. Don't use stock
photos.

## Visual style

Match the screens the user already approved and that the app already uses.
If you can open https://claude.ai/artifact/KFs7XutqQQWvyFkX3aEsyw, match it.
Otherwise:

- **Look:** Apple-style frosted glass panels over a soft colored gradient,
  in dark and light. Rounded cards, a blue accent (about `#1a6ad8`), and
  Roboto-like type.
- **Top bar:** a model chip, a project chip, and a settings gear.
- **Bottom bar on the Analyze screen:** round Stop and Pause buttons, and a
  wide "Project Media" pill with an item count. That pill opens this screen.
- **Earlier rough idea for this screen:** a search field, a filter row,
  chips, and a 3-column tile grid. The spec replaces the old
  Subject/Setting/Shot/Date selector with a categories row.
- **Phone frame:** 412 × 915 dp (Galaxy S26 Ultra), portrait.

## States to show, side by side, in dark and light

1. The default view:
   - the header (back, project name, item count, settings);
   - search;
   - the categories row with counts;
   - the secondary filters (photo/video, shot type, date);
   - the tile grid;
   - the pipeline status line, for example "40 files ready to bin".
2. One category selected.
3. The detail sheet:
   - the media and its description, actions and tags;
   - an editable friendly name;
   - a category picker that includes "New category…";
   - a visible lock or "set by you" marker;
   - Save.
4. Select mode with several tiles ticked, and the bulk actions: move to a
   category, and remove from project. Removing never deletes the file.
5. Category Edit: rename a category, and merge two.
6. Save in progress while it moves already-binned files. Show progress, and
   show that other edits wait.
7. The Re-sort project sheet:
   - a model picker that defaults to the Settings choice;
   - "Keep my edits", ticked by default;
   - what will and won't change (only the database changes; binned files
     move at the next Bin Files);
   - a cost note when Claude is picked.
8. A Failed item: its badge and the retry, set by hand, and remove options.
9. Search results.
10. An empty project.

Bin Files itself belongs to a later plan. Show its button and when it is
enabled, as the spec describes, but don't design its flow.

A few taps should work: open a category, open the detail sheet, enter select
mode, and open the Re-sort sheet.

## Deliver

1. `docs/superpowers/mockups/2026-10-31-plan-2b-project-media.html`: one
   self-contained file with no external dependencies except Google Fonts.
2. `docs/superpowers/mockups/2026-10-31-plan-2b-questions.md`: the open
   design questions the spec leaves to the user, numbered and most important
   first, each with a recommended answer. Include every *(my call)* in the
   spec's Project Media section.
3. A PR against `main` titled "Plan 2b: Project Media mockups (for review)"
   that contains only those two files. The PR description says what each
   state shows and links the questions file.

**Done** means the PR exists and the HTML renders correctly in a phone-width
browser.
