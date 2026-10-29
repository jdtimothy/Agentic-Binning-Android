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
