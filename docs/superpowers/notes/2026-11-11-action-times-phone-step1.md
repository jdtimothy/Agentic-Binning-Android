# Action times: Step 1 phone check

## Scope and gate

Followed `docs/notes/android-action-times-brief.md` from
`jdtimothy/Agentic-Binning-Windows`, branch `claude/action-times-by-motion`
(retrieved through GitHub on 11 November 2026; brief references `a4cc8b6`).
Read Android `CLAUDE.md` and `Instructions.md` first.

**Step 1 only. No application, prompt, grammar, or test code changed; no APK
was built or installed. Step 2 requires the owner's answer.**

## Environment and procedure

- Android repository baseline: `bcb9b61`; branch `test/android-action-times-phone`.
- Phone: Galaxy S26 Ultra / SM-S948U1, serial `R3GYC0JJDYD`.
- Installed release: version 1.0.2, versionCode 3; not debuggable.
- Model recorded in every analysis: `LiquidAI/LFM3-VL-2B-GGUF:Q4_0`.
- Prompt version recorded in every analysis: `analysis-v2`.
- Settings UI verified LFM3-1.7B selected (checked=true), Claude unchecked,
  and 16 frames selected before adding media. The local sorting model is not
  downloaded, so sorting did not run. No paid Claude call was made.
- Used the release app's existing New project / Add a folder UI, not
  instrumentation. Created `ActionTimesStep1` and analyzed copies of three
  real camera clips in `/storage/emulated/0/Movies/action-times-step1/`.
  Originals in `DCIM/Camera` were not modified. No Bin Files operation.
- Pulled the completed project database and queried `item JOIN analysis` for
  durations, replies, model, prompt version, stop reasons, and token counts.
- Read logcat to verify all three calls used the existing interleaved prompt,
  loaded on NPU with nCtx=16384, and released the model afterwards.
- Inspected local contact sheets extracted at all 16 `VideoTiming.sampleTimesMs`
  positions. These host-decoded sheets establish visible late events, not
  pixel-identical reproductions of Android's decoder output.

## Measurements

Times below are source sample positions in seconds, calculated exactly as
`durationMs * i / 16` with integer truncation, then displayed as seconds.
The supplied frame times are not claimed to be decoder presentation timestamps.

| Clip | Duration | Returned action times | Visible second-half evidence | Assessment |
| --- | ---: | --- | --- | --- |
| `20260919_120531.mp4` | 35.003 s | 0:00, 0:05, 0:10, 0:15 | Large spray plume at sample 19.689 s; another close splash at 28.439 s and spray/runoff at 30.627 s | Fails brief's second-half criterion: all actions before midpoint 17.5015 s; reply places a splash "near the end" at 0:15. |
| `20260919_121906.mp4` | 36.705 s | 0:00, 0:10, 0:20, 0:30 | Breaking waves/spray at 20.646 s, 29.822 s and 32.116 s | Passes spread criterion, but generic ongoing-wave phrases do not demonstrate accurate event localization. |
| `20260920_174506.mp4` | 47.362 s | 0:00, 0:05, 0:10, 0:15 | Small spurt at 32.561 s; clear upright spray at 38.481 s; larger plume at 41.441 s | Fails brief's second-half criterion: all actions before midpoint 23.681 s despite prominent late spray. |

### Full sample positions

- `20260919_120531.mp4`: 0.000, 2.187, 4.375, 6.563, 8.750, 10.938,
  13.126, 15.313, 17.501, 19.689, 21.876, 24.064, 26.252, 28.439,
  30.627, 32.815 s.
- `20260919_121906.mp4`: 0.000, 2.294, 4.588, 6.882, 9.176, 11.470,
  13.764, 16.058, 18.352, 20.646, 22.940, 25.234, 27.528, 29.822,
  32.116, 34.410 s.
- `20260920_174506.mp4`: 0.000, 2.960, 5.920, 8.880, 11.840, 14.800,
  17.760, 20.720, 23.681, 26.641, 29.601, 32.561, 35.521, 38.481,
  41.441, 44.401 s.

### Exact stored actions

`20260919_120531.mp4`:

- 0:00 — Waves crashing against rocks
- 0:05 — A wave breaks and creates white foam
- 0:10 — The waves continue to surge toward the shore
- 0:15 — A large splash hits the rocks near the end of the video

`20260919_121906.mp4`:

- 0:00 — The scene is calm with gentle waves
- 0:10 — Waves become more forceful and start breaking against rocks
- 0:20 — The intensity of the waves increases, creating white foam
- 0:30 — The scene remains consistent with ongoing wave action

`20260920_174506.mp4`:

- 0:00 — Waves crashing against the rocks
- 0:05 — Water splashes up from the sea
- 0:10 — Waves continue to crash and create a spray
- 0:15 — The scene remains consistent with slight changes in wave movement

### Execution results

| Clip | Stop | Generated tokens / cap | TTFT | Logcat generation interval |
| --- | --- | ---: | ---: | --- |
| `20260919_120531.mp4` | eos | 233 / 520 | 11414.251 ms | 21:06:42.950–21:07:00.854 |
| `20260919_121906.mp4` | eos | 238 / 520 | 12608.944 ms | 21:07:04.214–21:07:23.212 |
| `20260920_174506.mp4` | eos | 194 / 520 | 12789.031 ms | 21:07:26.536–21:07:44.587 |

All three items persisted as `Analyzed`. Model release logged at 21:07:44.830;
service became Idle at 21:07:45.149 (phone logcat times).

## Conclusion for the owner

**Android also exhibits unreliable action timing:** two of the three clips
omit all second-half action times despite visible late events. Unlike the
Windows example, the returned times are not literally the first sample times;
they use convenient 5- or 10-second increments. This demonstrates the
second-half failure criterion, not identical behavior or an identical cause.
One clip distributes times across its length, so the failure is not universal.
All three are related coastal-wave scenes; this is a targeted reproduction,
not a broad accuracy benchmark.

Stop here for the owner's decision before Step 2. Neither the optional photo
whitespace change nor allowing empty actions for still clips was implemented.

## Preserved state and remaining artifacts

- Restored initial current project `Test` (59 items), verified via UI dump.
- Restored DND to initial zen_mode=0 and read it back.
- No model-selection/frame-cap changes were saved: LFM3-1.7B and 16 remained
  selected. A download confirmation opened while checking the local row and
  was cancelled; no model download was initiated.
- `Test` and `Maui` project databases compared SHA-256-identical before/after.
- Kept `ActionTimesStep1` (three analyzed items) and the three source copies on
  the phone for inspection; nothing was removed from the current-project list.
- Local scratch evidence (not committed, contains personal media):
  `C:/Users/joshu/AppData/Local/hermes/cache/scratch/`:
  `action-times-step1.db`, `action-times-step1-logcat.txt`,
  `action-times-{test,maui}-{before,after}.db`, the three source videos,
  `*-samples.jpg`, `*-sample-<index>.jpg`, and initial `*-contact.jpg` sheets.
  Scratch files can expire; the exact actions, samples, and execution results
  are recorded above. No personal-media images or databases are in the PR.
