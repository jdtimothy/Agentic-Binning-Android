# Changelog

All notable changes to Agentic Binning are listed here. The project uses
[Semantic Versioning](https://semver.org/) — see the Versioning section of
the [README](README.md).

## [1.0.3] — 2026-11-12

- Swipe left or right through the cards on the Analyze screen to review
  each clip's saved description, actions, and tags.
- Keep the selected clip in view while analysis continues, with a
  **Follow analysis** button to return to the active clip. Long descriptions,
  actions, and tags remain scrollable up and down inside the Description card.
- Video actions now use moments chosen from visible changes across the clip,
  with times taken from the sampled frames instead of guessed by the model.
  Repeated descriptions are removed, and quiet clips can have no actions.
  Small events can still be missed; existing saved analyses are unchanged.

## [1.0.2] — 2026-11-11

- Fixed the themed (monochrome) launcher icon: replaced the original
  silhouette with a clean line-art glyph — three media cards with a play
  button on the center card.

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
