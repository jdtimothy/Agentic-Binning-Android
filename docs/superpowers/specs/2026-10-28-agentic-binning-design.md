# Agentic Binning for Android: design

Oct 28, 2026 · status: draft for approval

## Intent

Phone footage piles up as `VID_20260718_175109.mp4` beside hundreds of
siblings. The desktop Agentic Video Binning pipeline
(`..\Agentic-Video-Binning`) fixed this on a Snapdragon PC.
This app does the same job on the Galaxy S26 Ultra. You make a project, add
photos and clips to it, and a vision model on the phone's NPU describes each
item. A second model sorts the items into sensible categories. When you're
happy with them, one button moves every file into a category folder under a
short, readable name.

Two things make it worth building. First, analysis is local: frames never
leave the phone. Second, the categories are curated rather than a pile of
raw tags: a second agent works to keep the number of bins sensible.

## Goals

- Create a project, then add media to it: a whole folder, or a hand-picked
  batch of photos and clips. More media can be added later.
- Analyze every item on the NPU with LFM3-VL-2B: a description, a
  timestamped action list for videos, keyword tags and a suggested short name.
- Keep analysis running with the screen off or the app in the background.
- After each batch, automatically categorize the new items with the sorting
  model chosen in Settings.
- Browse, search and filter the project on the Project Media screen, and
  edit categories and names there, for one item or many at once.
- Move files into category folders only when the user presses **Bin Files**
  (or saves an edit to files already binned), with checks that guarantee no
  clip is lost or overwritten.
- Dark and light mode, built from glass elements, matching the approved
  mockups.

## Non-goals (for now)

- Undo for Bin Files or Save.
- Other folder layouts (date, place, nested). This may become a sort-method
  setting later.
- Splitting videos into shots. A file can live in only one folder, so the
  clip is the unit of analysis.
- Natural-language search through a model. Search is full-text.
- More than one vision model in the picker.
- Play Store distribution. This is a sideloaded personal app.

## Anti-goals (failure even if it "works")

- Any file lost, truncated or overwritten. This is the one unforgivable
  failure.
- A long tail of near-duplicate or one-item categories ("sea lion", "sea
  lions", "seals on rocks").
- Catch-all bins that lump unrelated things together.
- Files moving without the user pressing Bin Files, or Save on an edit to
  items whose files are already binned.
- Images or frames sent to the cloud. Claude only ever sees text.
- Two models loaded at once, which pushes the phone into killing apps.

## Constraints

- Device: Galaxy S26 Ultra (SM-S948U1, SM8850, 12 GB RAM, Android 17).
- SDK: `com.qualcomm.qti:geniex-android:0.10.0`. Follow every item in
  `Instructions.md`, especially its gotchas checklist: `nBatch = 2048` /
  `nUBatch = 512`, an explicit `maxTokens` and `SamplerConfig`, absolute file
  paths for images, `OPTION_CLOSEST` frames padded to a square, and the
  media-splice workaround for interleaved video timestamps.
- Build: AGP 8.13.0, Kotlin 2.2.0, Gradle 9.1.0, compileSdk 35, targetSdk 34,
  minSdk 31, `jniLibs.useLegacyPackaging = true`. No Android Studio; build
  with `./gradlew` from Git Bash.
- Memory: only one model is loaded at a time. The vision model runs with
  a 16K context, not its full 128K.

## Starting point: a fresh app, not the sample

The folder currently holds a renamed copy of the GenieX chat sample. Replace it
with a new app. Port only these pieces, and rewrite them where needed:

- Model download (`ModelManagerWrapper.pullFlow`, progress, wake lock) and the
  load path (`VlmWrapper` / `LlmWrapper` create inputs from `getPaths()`).
- `GgufVisionConfig.kt`, which reads vision geometry from the mmproj.
- Image decode and resize (`ImageDecoder`, software allocator, longest edge
  ≤ 1,536 px, pad to a square) and video frame extraction.
- The `<__media__>` splice into `formattedText` for video frames.

The chat UI, chat adapter, Markwon, immersionbar, the model catalog dialog
and the `androidTest` chat test all go. Package `com.agentic.binning` stays.

## Concepts

| Term | Meaning |
| --- | --- |
| Project | A named folder on the phone that holds the project's database and, once files are binned, its category folders |
| Item | One photo or video file added to a project |
| Batch | The items queued for analysis that finish together; a batch ends when the analysis queue empties |
| Tags | Keywords the vision model gives each item; many per item, used for search and filters |
| Category | A bin chosen by the sorting model; each item has exactly one, which becomes its folder |
| Friendly name | A short readable filename an item gets when it is binned |
| Sorted | An item that has a category. Every analyzed item is sorted as soon as the sorting model runs |
| Binned | An item whose file has been moved into its category folder under its friendly name |

## Screens

The approved mockups are at https://claude.ai/artifact/KFs7XutqQQWvyFkX3aEsyw.
These changes were decided after the mockups were approved:

### Top bar (Analyze screen)

- **Vision model picker** (left): LFM3-VL-2B only, for now. It shows the
  download state on first run and pulls the model when tapped.
- **Project button** (middle): shows **Create project** when no project
  exists. Once a project is open, it shows the project name. Tapping it opens
  a sheet with: add media (a folder, or pick clips), create a new project,
  and switch to another project. *(The sheet is my call.)*
- **Settings** (far right).

### Analyze screen

As mocked up: a 3D card carousel. The next item waits, tilted and dimmed, on
the right. The current item rotates into the center while it is analyzed.
Finished items slide back to the left and stay partly visible. Below the
carousel, the agent output card streams the description, then the Action
timeline (videos only), then tags.

Bottom bar: **Stop**, **Pause/Resume**, and **Project Media** (renamed from
Bin, still showing a count).

- Pause holds before the next item starts. *(My call: the current item
  finishes first.)*
- Stop ends the run and releases the model. Items left in the queue stay
  pending and start again on Resume.

### Project Media screen (renamed from Bin)

- Header: back, project name, item count, settings.
- Search: full-text over descriptions, actions, tags, friendly names and
  category names.
- Filters: the first row is the project's **categories** (the bins), with
  counts. *(My call:* secondary filters for photo/video, shot type and date
  replace the mockup's Subject/Setting/Shot/Date selector, because
  categories are now the main way to group.)*
- Tile grid as mocked up. Tapping a tile opens a detail sheet: the media, its
  description, actions and tags, the friendly name (editable) and the
  category (changeable).
- **Editing, single and bulk.** Nothing changes until you press **Save**.
  - *One item*: in the detail sheet, change its category (pick an existing
    one or make a new one) and its friendly name.
  - *Many items*: a select mode (long-press a tile, or a Select button)
    lets you tick several tiles. You can then move them all to one category
    (existing or new) or remove them from the project.
  - *Categories*: **Edit** on the categories row lets you rename a category
    or merge two.
- **What Save does.** It updates the database. For items whose files are
  already binned (moved into their category folder), it also moves or
  renames those files right away, with the same safety rules as Bin Files:
  a written plan, one file at a time, verified, never overwriting (number
  suffixes), and a count check at the end. Renaming a category whose folder
  exists renames the folder. Merging moves the files into the surviving
  folder. Items whose files aren't binned yet only change in the database;
  their files move at the next Bin Files. While a Save is moving files, it
  shows progress and other edits wait.
- **Re-sort project** (an action on Project Media): re-runs the sorting
  model over every item in the project from scratch, so it may restructure
  all the categories. This run can use a different model from the one in
  Settings; the picker defaults to the Settings choice. Categories and
  items you set yourself are kept unless you untick **Keep my edits**,
  which is ticked by default *(my call)*. Re-sorting changes only the
  database. Binned files whose category or name changed move at the next
  Bin Files, and the status line shows how many are waiting.
- **Bin Files** button (renamed from Bin and sort). Every categorized item
  is already sorted; this moves files that are not yet in their category
  folder. It is enabled when there are files waiting to move, no item is
  pending, analyzing or waiting to be categorized, and nothing else is
  running. Failed and missing items don't block it (see step 4). While it
  runs, it shows progress.
- Failed items show a badge. They can be retried, given a category and name
  by hand, or removed. **Remove from project** deletes only the item's
  database row and never the file. *(My call.)*
- A status line for the pipeline: "Analyzing 12 left", "Categorizing…",
  "40 files ready to bin", "All files binned".

### Settings

- **Sorting model**: Claude (cloud), LFM3-1.7B (on device) or Qwen3-8B (on
  device). Choosing a local model offers to download it.
- **Claude API key**: stored encrypted on the device. If Claude is selected
  without a key, the app asks for one.
- **Keep screen on** while the app is visible and analysis is running.
- Theme: System, Dark or Light. *(My call.)*
- Leave space for a future "sort method" setting.

## Pipeline

```
add media → register → analyze (vision, NPU) → batch done → release vision model
          → load sorting model → categorize batch (items are now sorted) → release
          → [user] Bin Files (files move into category folders)
```

### 1. Create project and add media

- **Create project** asks for a name and creates
  `/storage/emulated/0/Agentic Binning/<name>/`, with the database in
  `<name>/.binning/project.db`. The database lives with the data, as on
  desktop. *(Location is my call.)*
- **Add media** offers a folder picker or a multi-select picker for photos and
  videos. Each file is registered with its path, original name, kind, capture
  time, duration, dimensions and a **content hash**. If a file's hash is
  already in the project, it is skipped as a duplicate.
- Files stay where they are until Bin Files.
- **A file belongs to only one project.** The app keeps a small registry of
  its projects (name and folder) in app-private storage. Adding a file that
  another project already holds (same path or same hash), or a file inside
  another project's folder, is refused with a message naming that project.
  This stops binning in one project from moving a file another project
  still points to. *(My call; the mechanism is up to the builder.)*

### 2. Analysis

- It runs in a foreground service with a progress notification, holding a
  partial wake lock, so it continues with the screen off or the app in the
  background.
- **Photos**: decode and resize, then pass one image.
- **Videos**: frames at 2 fps, capped at 16, spread evenly over longer clips,
  using `Instructions.md`'s timestamped `Video:` format and splice
  workaround.
- Each item gets one single-turn call with a fixed system prompt that asks for
  one JSON object: `description`, `actions` (videos only: `[{t, text}]`),
  `tags` (about 5–10), `suggested_name` (2–5 words, no extension) and
  `shot_type`. Cap the reply at about 400 tokens. Retry once on
  `stopReason = length` or invalid JSON. After the retry, mark the item
  failed; it can be retried, and it does not block the batch. Try a GBNF
  grammar first, as `Instructions.md` suggests.
- Store `model_id`, `prompt_version` and the timing profile with every
  analysis.
- The Analyze screen shows the streaming output live.

### 3. Categorize (automatic, after each batch)

- When the queue empties, the service releases the vision model, runs the
  sorting model chosen in Settings, then releases that too.
- **The sorting model sees text only**: for each item, its id, kind,
  description, actions, tags and suggested name; plus the project's existing
  categories with a short description and item count. It never sees images,
  frames or full file paths.
- It returns: categories (name and one-line description), an assignment of
  every new item to one category, and a final friendly name for each new item.
- **Each automatic run sorts only the new batch.** The model sees the new
  items plus the project's category list. New items go into existing
  categories, and the model adds a new category only when nothing fits
  well. On a project's first batch there are no categories yet, so the
  model creates them, consolidated as described below. Existing categories
  are never restructured by an automatic run; the way to restructure is
  **Re-sort project**, which sees every item.
- **What the user set is locked.** A category the user created or renamed can
  receive new items, but the model can't rename, merge or remove it. An item
  whose category the user chose is never reassigned. *(My call.)*
- Rules for the categories, in the prompt and checked in code afterwards:
  - no two categories whose names normalize to the same thing (case,
    plurals, punctuation);
  - avoid one-item categories unless the item truly fits nowhere else;
  - no catch-all bin unless a handful of items really fit nowhere;
  - category names are short nouns that make good folder names.

  The exact target size and the wording of the prompt are for the builder
  to tune on real footage.
- Local models may not fit a large project in one context, especially a fixed
  `qairt` bundle. Keep each item to one compact line, and chunk if needed:
  make categories from summaries first, then assign items in groups.
- If Claude is chosen and there's no key or no network, items stay
  "uncategorized" and the status says so. Categorizing retries on the next
  batch, or from a "Categorize now" action on Project Media.
- Users' edits to categories and names on Project Media are kept. The model
  never overwrites a name or category that the user set.

### 4. Bin Files (user-started) and Save moves

Bin Files and a Save that touches binned files use the same file-move
engine.

1. **Plan.** In a single transaction, write a move plan to the database.
   It covers every sorted item whose file is not at its target, where the
   target is `<project>/<Category>/<friendly name>.<original extension>`.
   That means items never binned, plus binned items whose category or name
   changed after a Re-sort project. If the target name is already taken in
   the folder (on disk or elsewhere in the plan), add ` 2`, ` 3`, and so on.
   Never overwrite. Failed items without a category, and missing files, are
   left out. They stay where they are and are listed in the report.
2. **Move, one file at a time.** Copy to the destination, check that size and
   content hash match the original, update the item's path in the database,
   then delete the original. Each step is recorded in the plan, so a crash
   or reboot resumes where it stopped. At every moment, every file exists
   either at its source or verified at its destination. A category folder
   left empty is removed only if it is truly empty.
3. **Verify.** The number of files across all category folders must equal the
   number of binned items in the project: the items in this plan plus any
   binned earlier. Every item's path must exist, and no unexpected files may
   be present. On any mismatch, stop, move nothing more, and show which items
   are missing or extra.
4. **Report.** For example: "248 files binned into 14 category folders."

This also runs in the foreground service. Pause, Stop and analysis controls
are disabled while it runs.

## Data model (starting point)

Adapted from `Instructions.md`, with shots removed and sorting and binning
added:

| Table | Key columns |
| --- | --- |
| `item` | `id`, `content_hash`, `original_path`, `original_name`, `current_path`, `kind`, `taken_at`, `duration_ms`, `width`, `height`, `status` (pending / analyzing / analyzed / failed / sorted / binned / missing), `category_id`, `friendly_name`, `name_set_by_user`, `category_set_by_user` |
| `analysis` | `item_id`, `description`, `actions_json`, `suggested_name`, `shot_type`, `raw_json`, `model_id`, `prompt_version`, `stop_reason`, `ttft_ms`, `generated_tokens`, `analyzed_at` |
| `tag`, `item_tag` | normalized tags |
| `category` | `id`, `name`, `description`, `created_by` (model / user), `folder_name` |
| `item_fts` | FTS5 over description, actions, tags, friendly name, category name |
| `move_run`, `move_step` | the plan and per-file progress for Bin Files and Save: `source`, `dest`, `state` (planned / copied / verified / done / failed) |
| `project_meta` | name, created_at, schema version |

## Models

| Role | Model | Where | Notes |
| --- | --- | --- | --- |
| Vision | LFM3-VL-2B (`LiquidAI/LFM3-VL-2B-GGUF`, Q4_0 + F16 mmproj) | NPU, `llama_cpp` | `nCtx` 16K, model-card sampling (temp 0.1, top_k 50, top_p 1.0, min_p 1e-6, rep 1.1) |
| Sorting | Claude (default: Sonnet 5.5, `claude-sonnet-5-5`) | Cloud, Messages API | Text only. Use tool use or structured output for the JSON; check the claude-api skill when building |
| Sorting | LFM3-1.7B (`LiquidAI/LFM3-1.7B-GGUF`, Q4_0) | NPU, `llama_cpp` | General preset sampling; context sized to the job |
| Sorting | Qwen3-8B | NPU | Prefer the AI Hub `qairt` bundle (`ai-hub-models/*`, `chipset = "SM8850"`, `nCtx = 0`, `nGpuLayers = 0`) if it pulls and loads with geniex 0.10.0. Otherwise use a Qwen3-8B Q4_0 GGUF on `llama_cpp`. If neither works, leave it out of Settings and say so. *(My call.)* |

## Storage and permissions

- **All files access** (`MANAGE_EXTERNAL_STORAGE`) is requested when the first
  project is created. The app moves files it didn't create (for example
  from `DCIM/Camera`) and keeps a database beside them, which scoped storage
  makes painful. Fine for a sideloaded app. *(My call.)*
- After a move, notify MediaStore (scan the new path, remove the old one) so
  the gallery stays correct.
- Notifications permission is needed for the foreground service notification.

## UI build notes

- Jetpack Compose with Material 3 as a base, styled to the mockups. Backdrop
  blur via `RenderEffect` (minSdk 31 allows it) or a library such as Haze.
  *(Builder's choice.)*
- Theme follows the system by default; both themes come from one token set,
  as in the mockups.
- Ambient blurred color behind the glass is taken from the current item.

## Left to the builder

- Prompt wording, JSON schema details and the category-size heuristics,
  tuned on real footage.
- Category-editing interactions on Project Media, and the detail sheet's
  layout.
- Concurrency inside the service (decode ahead while the NPU works).
- Moves within the same storage volume may use an atomic rename, checked
  afterwards, rather than copy, verify and delete. Moves across volumes (an
  SD card, for example) always copy, verify, then delete.
- Database library (Room or plain SQLite, as long as FTS5 works).
- How the Analyze carousel behaves when a project has no pending items (an
  idle or empty state).
- Verification hash choice (for example SHA-256, or a fast hash plus size).
