# Plan 3 cloud handoff

Ledger of the cloud session that planned and started Plan 3 on
`plan-3-binning` (Nov 4, 2026, no phone attached). Each entry is a ruling
(`Ruling: what — why — cost if wrong`) or a finished task
(`Task N: complete (commits …, tests: …)`). The summary for the next session
is at the end once the session finishes.

## Ledger

- Merged `origin/main` @ `738cfad` (PRs #22, #23) into `plan-3-binning` as `981766f`; a real merge, no rebase. Baseline: `testDebugUnitTest` 596 tests in 48 classes, 0 failures; `assembleDebug assembleDebugAndroidTest` succeed (after Maven 429 retries).
- Owner answers on Nov 4 (in conversation with the cloud session): B1 rename within a volume, B2 Pause and Stop after the current file, B3 resume on its own, B4 retry once then skip, B5 block unknown files with Add / Leave, B6, B9, B10 include binned items, B12, Q2's details, Q11 yes, P8 the owner's own copy design. Open: B7, B8, B11.
- Ruling: Status Binned is kept in step with the file (set by the engine; a Re-sort that changes a binned item's target sets it back) and "ready to bin" is decided by path — plan-2-notes asks for path, and the tile badge still needs a status — cost if wrong: one counting function.
- Ruling: The folder check ignores hidden files (names starting with ".") — they are never media, and blocking Bin Files on a `.nomedia` or a gallery cache would strand the user — cost if wrong: one filter in `FolderCheck`.
- Ruling: An item whose path another listed project also holds (possible only when one of them was off the list) is copied into its category folder and its original left in place — the owner's P8 answer says another project's files are copied, never moved — cost if wrong: the `keepSource` flag.
- Ruling: A rename whose check fails stops the run instead of skipping (B4 covers copies) — a rename can't be undone by skipping, and it means the storage misbehaves — cost if wrong: one branch in the engine.
- Ruling: A step started a third time (interrupted twice) is skipped with `interrupted` — B3 asks that a file that crashes the app can't loop; it mirrors analysis' attempts rule — cost if wrong: one constant.
- Ruling: A no-replace rename is `Files.move` without `REPLACE_EXISTING` after an existence check — Android's FUSE has no hard links for an atomic no-replace, and only this app writes into category folders — cost if wrong: a name clash in a sub-millisecond window, which the engine's post-rename check catches.
