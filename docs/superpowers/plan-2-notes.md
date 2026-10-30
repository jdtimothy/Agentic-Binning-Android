# Notes for Plan 2

Decisions and open questions raised during Plan 1 that the next plan must
pick up. Read this before writing Plan 2.

## Video frames per clip (decided 2026-10-29)

- Plan 1 samples at 2 fps with a fixed cap of 16 frames, spread evenly over
  clips longer than 8 s. Long clips get sparse (a 2-minute clip gets one frame
  every 7.5 s).
- Add a Settings option: **maximum frames per clip**. It is a cap, not a fixed
  count: frames = min(duration × 2, cap). Never sample faster than 2 fps
  (the rate LFM3-VL was trained on), so short clips are unaffected (a 5 s clip
  always gets 10 frames).
- **No time estimate** next to the setting: speed depends on the model, so it
  can't be predicted. Showing only the resulting frame count (for example
  "1-minute clip: 32 frames, one every 1.9 s") is fine if a hint is wanted.
- **Context window is sized automatically** from the cap (about 260 tokens per
  frame, plus prompt, reply and margin); users never set it. Don't allocate a
  large window up front: memory is reserved in full, and a 256K window once
  made Android close seven other apps.
- Design this together with **shot splitting** (Instructions.md, "unit of
  analysis = a shot"): split long videos at scene changes and give each shot
  up to the cap. A frame cap alone leaves multi-second gaps on long clips.

## Carried from Plan 1 reviews

- The batch-complete handler runs once per rerun batch, so the sorter may run
  twice when media is added mid-batch.
- `search()` input must be sanitized for FTS5.
- Re-saving an analysis blanks the FTS `friendly_name` and `category`; refresh
  them.
- Deleting an item must also delete its `item_fts` row.

## From the Plan 1 final review

- **One arbiter for "one model loaded".** Today the rule holds only because
  `runBatch` releases the vision model before `onBatchComplete`. Re-sort
  project, Categorize from Project Media and Plan 3's Bin Files must go
  through the controller or service, using a job kind (analysis, sorting,
  binning) and a mutex. Separate code paths would let a re-sort during
  analysis load a second model.
- **Add a `Categorizing` phase.** The batch-complete handler currently runs
  under `Releasing` ("Finishing up").
  - Stop and project switches cancel the handler, so sorting must be
    cancellable and must release its own model in `finally`.
  - Pick the work by status (Analyzed and not yet sorted), not by "this
    batch".
- **Schema migrations never destroy data.** Project databases sit next to the
  user's media, and other projects' databases are opened during registration,
  so migrations also run on projects that aren't open. Room schema JSON is
  exported from Plan 1's final fixes onward.
- **Registration rebuilds the cards on every insert.** Each insert triggers
  `observeAll()`, so a large add rebuilds all cards once per file (O(n²)). It
  worked at 1,600 items. Insert in chunks inside a transaction, or debounce
  the card flow.
- **For Plan 3 (binning):**
  - Exclude `.binning/` (database, journal, thumbnails) from the "no
    unexpected files" count check.
  - Decide "already binned" from `currentPath` against the target path, not
    from `status` alone. Status mixes analysis, sorting and binning state.
