# Changelog

All notable changes to Agentic Binning are listed here. The project uses
[Semantic Versioning](https://semver.org/) — see the Versioning section of
the [README](README.md).

## [1.0.1] — 2026-11-11

- New launcher icon: a carousel of media cards — an ocean wave, a sunset,
  and a rainforest — with adaptive and themed (monochrome) variants.

## [1.0.0] — 2026-11-11

First public release.

- Pick a project folder of photos and videos and analyze every item
  on-device with LFM3-VL-2B running on the Snapdragon NPU via GenieX —
  each file gets a friendly name, a short description, tags, a category,
  and a shot type.
- **Bin Files**: move analyzed media into category subfolders in one tap.
- Search a project by keyword, and filter by category or by photo/video.
- Re-sort and edit categories after analysis.
- The model downloads from Hugging Face on first use; analysis is fully
  on-device and offline after that.
- Optional cloud sorting help with Claude: refines categories using only
  the local model's text (names, descriptions, tags) — photos and videos
  never leave the device (bring your own Anthropic API key).
