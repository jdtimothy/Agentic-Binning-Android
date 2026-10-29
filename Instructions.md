# Building On-Device Vision Apps with GenieX on Galaxy S26

Oct 28, 2026 · @Joshua Timothy

## Overview

Start from the patched `geniex_chat_android` sample, not from scratch. It now runs LFM3-1.7B and LFM3-VL-2B on the Galaxy S26 Ultra's NPU with geniex 0.10.0. Every fix below was found and confirmed on that phone on Oct 28, 2026.

| Item | Value |
| --- | --- |
| Device | Galaxy S26 Ultra (SM-S948U1), Snapdragon 8 Elite Gen 5 (`SM8850`), 12 GB RAM, Android 17 |
| SDK | `com.qualcomm.qti:geniex-android:0.10.0` (Maven Central, released Oct 28, 2026) |
| Text model | `LiquidAI/LFM3-1.7B-GGUF`, file `LFM3-1.7B-QAD-Q4_0.gguf` (1.05 GB), 256K context |
| Vision model | `LiquidAI/LFM3-VL-2B-GGUF`, `LFM3-VL-2B-QAD-Q4_0.gguf` + `mmproj-LFM3-VL-2B-F16.gguf` (379 MB), 128K context |
| Runtime | `llama_cpp` on the Hexagon NPU (`compute_unit = "npu"`) |
| Reference code | `..\geniex-android` (the patched demo) |

Measured on the phone:

| Workload | Time to first token | Decode speed |
| --- | --- | --- |
| LFM3-1.7B text, 1K context | 53 ms | 47 tok/s |
| LFM3-1.7B text, 256K context | 57 ms | 43 tok/s |
| LFM3-VL-2B, 1 image (281 prompt tokens) | 0.6 s | 46 tok/s |
| LFM3-VL-2B, 16-frame video (4,178 prompt tokens) | 7.4 s | 39 tok/s |
| LFM3-1.7B after 13,000 generated tokens | n/a | 31 tok/s |

The sections follow the order you will hit them: toolchain, downloads, loading, prompting, images, video. Then come an architecture for a photo and video library app, debugging, a gotchas checklist, and upstream bugs.

## Toolchain and project setup

You do not need Android Studio. A JDK, the Android command-line tools and a Gradle wrapper build and install the APK from Git Bash on this Windows ARM PC.

1. Install JDK 17: `winget install --id Microsoft.OpenJDK.17 -e`. It lands in `C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`.
2. Unzip the Android command-line tools (`commandlinetools-win-13114758_latest.zip`) into `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`.
3. Accept licenses and install packages: `sdkmanager --licenses`, then `sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"`.
4. Write `local.properties` with `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`.
5. Download Gradle 9.1.0 once and run `gradle wrapper --gradle-version 9.1.0`. After that, always use `./gradlew`.
6. Build and install: `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot" ./gradlew assembleDebug && adb install -r -t build/outputs/apk/debug/app-debug.apk`.

Gradle settings that work with geniex 0.10.0:

| Setting | Value | Why |
| --- | --- | --- |
| Android Gradle Plugin | 8.13.0 | Matches the sample; works with Gradle 9.1.0 |
| Kotlin | 2.2.0 | Matches the sample |
| `compileSdk` / `targetSdk` | 35 / 34 | Target 35 forces edge-to-edge layouts on Android 15+ |
| `minSdk` | 31 | The sample's floor; geniex itself allows 27 |
| `ndkVersion` | remove it | Not needed; the AAR ships prebuilt `arm64-v8a` libraries |
| `jniLibs.useLegacyPackaging` | `true` | Keeps the native libraries extracted on disk for the NPU loader |
| Dependency | `implementation 'com.qualcomm.qti:geniex-android:0.10.0'` | The docs still say 0.3.1; ignore that |

The debug APK is about 104 MB because it bundles every Hexagon and QNN library. Model weights are not in the APK; they download at runtime.

The Maven sources JAR for 0.10.0 holds only a README. To read the Kotlin API, clone `qualcomm/GenieX` at tag `v0.10.0` and look in `bindings/android/app/src/main/java/com/geniex/sdk`. The native plugin code is in `sdk/plugins/llama_cpp/src`.

## Downloading models

Let the SDK download weights with `ModelManagerWrapper.pullFlow`; never `adb push` them by hand. Pulls resume after a cancel or a crash.

```kotlin
GenieXSdk.getInstance().init(context) // safe on every onCreate
ModelManagerWrapper.pullFlow(
    ModelPullInput(
        model_name = "LiquidAI/LFM3-VL-2B-GGUF",
        precision  = "Q4_0",
        hub        = HubSource.HUGGINGFACE,
    )
).collect { event -> /* Progress(files) / Completed / Error(code, message) */ }
val paths = ModelManagerWrapper.getPaths("LiquidAI/LFM3-VL-2B-GGUF") // null until complete
```

- **Precision matching:** `"Q4_0"` picked `LFM3-1.7B-QAD-Q4_0.gguf`, the quantization-aware build Liquid recommends. Check which file landed with `adb shell run-as <package> ls files/geniex/models/<org>/<repo>`.
- **Vision projector:** for a VLM the pull also fetches an `mmproj` file. It chose `mmproj-LFM3-VL-2B-F16.gguf`, the one Liquid benchmarks with.
- **Use `getPaths()` output as-is:** pass `model_path`, `mmproj_path`, `tokenizer_path` and `runtime_id` straight into the create input; `runtime_id` from the manifest wins over any UI choice.
- **AI Hub NPU bundles** (`ai-hub-models/*`, runtime `qairt`) need `chipset = "SM8850"` on this phone. Auto-detect only works on Windows. The sample's catalog shipped with `SM8750`, which is the previous chip.
- **Progress:** sum `downloaded_bytes` over `total_bytes` across `event.files`. Hold a partial wake lock while pulling; the sample does.
- **Disk:** LFM3-1.7B is 1.05 GB, and LFM3-VL-2B is 1.05 GB plus a 379 MB projector. Downloads ran at about 6% per 5 s on Wi-Fi, roughly 80 s each.

The model pages on AI Hub list Galaxy S26 support but no download steps. The real identifiers are the Hugging Face repos in the pages' `model_infer_id` field.

## Loading models

Load both LFM3 models with `runtime_id = "llama_cpp"` and `compute_unit = "npu"`, and keep `nBatch` at 2048 or the load aborts. Set `nCtx` to the full trained length only if you budget the memory below.

```kotlin
VlmWrapper.builder().vlmCreateInput(
    VlmCreateInput(
        model_path   = paths.model_path,
        mmproj_path  = paths.mmproj_path,
        config       = ModelConfig(nCtx = 128000, nThreads = 4, nBatch = 2048, nUBatch = 512, nGpuLayers = 999),
        runtime_id   = paths.runtime_id.ifEmpty { "llama_cpp" },
        compute_unit = "npu",
    )
).build() // Result<VlmWrapper>; LlmWrapper is the same with LlmCreateInput + tokenizer_path
```

- **Batch-size crash:** the sample set `nBatch = 1, nUBatch = 1` for VLMs. On 0.10.0 the warmup step decodes two tokens in one batch and hits `GGML_ASSERT(n_tokens_all <= cparams.n_batch)`, which kills the app (SIGABRT), not just the load.
- **API renames since 0.3.x:** `enable_thinking` moved out of `ModelConfig` into `applyChatTemplate(…, enableThinking)`; `model_name` left the create inputs; `nPast` left `GenerationConfig`. `LlmWrapper.generateStreamFlow` takes named `prompt =` and `config =`.
- **NPU proof:** logcat shows `libggml-htp-v81.so` opening on domain 3 (the cDSP). The `Permission denied` and `No such file` lines from `adsprpc` right before it are normal probing noise.
- **AI Hub `qairt` bundles:** pass `nCtx = 0` and `nGpuLayers = 0`; any other value is rejected because the bundle is compiled with a fixed context.

### Context length and memory

LFM3 makes long context cheap: of its 24 layers only 6 use full attention, 6 use a 256-token sliding window, and 12 are convolution layers. The cache costs about 12 KB per token (6 layers × 4 KV heads × 128 dims × 2 for K and V × 2 bytes).

| Model | `nCtx` | Weights | Context cache | Total NPU memory |
| --- | --- | --- | --- | --- |
| LFM3-1.7B | 262,144 | 1,008 MiB | 3 × 1,026 MiB | 4.3 GiB |
| LFM3-VL-2B | 128,000 | 1,008 MiB | 1,002 + 502 MiB | 3.4 GiB with the vision encoder |

These were measured by summing `rpcmem_dma_alloc` sizes in logcat during load. They don't show up in `dumpsys meminfo`, which reported only 394 MB for the app.

On a 12 GB phone, loading at 256K dropped free memory from 3.8 GB to 0.9 GB, and Android killed seven background apps to make room. Nothing crashed. For a library app that runs in the background, use a smaller window (32K is about 0.4 GB of cache), unless one job really needs a long transcript in context.

In the demo, the full context is set per model with a `contextLength` field in `model_list.json`. The VL model card states 128K (`max_position_embeddings = 128000`), even though it shares the 256K-trained language model.

## Prompting and generation

Always pass the templated prompt (`applyChatTemplate(…).formattedText`) to `generateStreamFlow`, and always set `maxTokens` and a `SamplerConfig` yourself. The SDK's defaults are not what LFM3 was tuned for.

### Sampling

| Setting | geniex llama\_cpp default | LFM3 model card (general use) |
| --- | --- | --- |
| temperature | 0.8 | 0.1 |
| top\_k | 40 | 50 |
| top\_p | 0.95 | 1.0 |
| min\_p | 0.05 | 0.0 (send `1e-6`) |
| repetition penalty | 1.0 (off) | 1.1 |

In `SamplerConfig`, `0` means "use the default" (`params.cpp:208`), so a real 0 cannot be sent; `minP = 1e-6f` turns min\_p off in practice. The 1.7B card also lists a Math & STEM preset (temperature 1.0, top\_p 0.95, top\_k 20). For tagging and describing media, use the general preset: you want the most likely description, not a creative one.

### Reply length

- **`maxTokens = 0` is not unlimited:** geniex turns it into 128 for text models (`llm.cpp:252`) and 512 for VLMs (`vlm.cpp:455`). The sample's comment saying otherwise is wrong.
- **When the context fills:** the text model silently drops the older half of the conversation and keeps going (`llm.cpp:357`). The VLM stops with stop reason `length`.
- **Runaway replies are real:** asked to spell out 1 to 1,000, LFM3-1.7B produced 13,351 tokens of self-talk in 7 minutes and never answered. It ignored the empty `<think></think>` block in the template. For background jobs, set a cap that fits the expected output (a JSON record needs about 300 tokens) and treat `length` as a failure to retry.

### Stop reasons and timing

`LlmStreamResult.Completed.profile` gives `stopReason` (`eos`, `length`, `user`, `stop_sequence`), plus `ttftMs`, `mediaMs`, `promptTokens`, `generatedTokens`, `prefillSpeed` and `decodingSpeed`. `length` covers both the reply limit and a full context; compare `generatedTokens` with your `maxTokens` to tell them apart. Log these for every job; they are the cheapest way to spot a regression.

### Multi-turn

The VLM tokenizes incrementally. On each turn, pass only that turn's images to `injectMediaPathsToConfig`. History must render byte-for-byte as it did before: a follow-up turn then prefills only its new tokens (22 in the demo). LFM3-VL's card says it is not meant for multi-turn chat or complex reasoning. For a library app, make each analysis a fresh single-turn call (`reset()` between jobs).

## Images

One photo costs LFM3-VL-2B about 256 tokens and 0.6 s to the first word. The native side reads image files from an absolute path, so decode, resize and save each photo to app storage first.

- **Picking:** use `ActivityResultContracts.PickMultipleVisualMedia` with `ImageAndVideo`. It needs no storage permission and returns content URIs. For a library app that scans the whole gallery, use `MediaStore` queries plus `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` instead.
- **Never pass a content URI** in `VlmContent("image", …)`. Copy or decode it to a file under `filesDir` and pass `file.absolutePath`.
- **Decode safely:** `ImageDecoder` with `ALLOCATOR_SOFTWARE`, downsampled so the longest edge is at most 1,536 px. It applies EXIF rotation and avoids running out of memory on 200 MP photos.
- **Vision geometry:** the demo's `GgufVisionReader` reads it from the mmproj: 512 px tiles, patch 16, merge 2, giving 256 tokens per image. Size preprocessing from those values, not a constant.
- **Square crop vs pad:** the sample center-crops to a square, which cuts the sides off wide shots. Pad to a square instead (black bars) whenever the edges matter, as for video frames.
- **Native resolution:** LFM3-VL is trained on images at native resolution. The square resize is a simplification inherited from the sample. For document or text-heavy photos, test sending the original aspect ratio and compare.

Measured: "HELLO S26" plus a red and a blue square was described correctly in 30 tokens, with a 0.58 s time to first token, of which 0.38 s was image encoding.

## Video

geniex 0.10.0 has no video input, so send video exactly the way llama.cpp's own video helper does for LFM3-VL: `Video:`, then frames at 2 fps, with a timestamp after the first frame and every 5 s. Then splice that sequence into the templated prompt yourself, because geniex's template step moves every image ahead of all the text.

### Why frames are the right answer here

- **The model supports video, but the API doesn't.** The LFM3-VL card lists video input sampled at 2 fps. geniex's C API has only `image_paths` and `audio_paths`. An `.mp4` passed as an image fails with `failed to load image 0 … Image loading failed`.
- **llama.cpp treats LFM3-VL frames as images anyway.** Temporal frame merging is only implemented for Qwen-VL, Nemotron-Omni and Ling-3VL (`clip_model_n_temporal_merge`); LFM3-VL gets 1, meaning none. Its video helper also needs an `ffmpeg` binary, which Android doesn't have.
- **So matching llama.cpp's format gives the model the same input it would get from `llama cli --video`.** The `<|video|>` token on the model card belongs to the Transformers version, not GGUF.

### The format (from `mtmd-helper.cpp`, llama.cpp `19cce69`)

```text
Video:<__media__>[0m0.00s]<__media__><__media__>…<__media__>[0m5.00s]<__media__>…
<your question>
```

- Frames at 0, 0.5, 1.0 s and so on (2 fps). Cap at 16 per clip; spread longer clips evenly over their full length.
- A timestamp in the form `[%dm%.2fs]` goes after the first frame at or past each 5 s mark, starting with frame 0.
- `<__media__>` is mtmd's default marker. llama.cpp fills each one with the next image path, in order.

### Extracting frames on Android

- **Use `MediaMetadataRetriever.getScaledFrameAtTime(t, OPTION_CLOSEST, …)`.** `OPTION_CLOSEST_SYNC` snaps to keyframes, and on a clip with one keyframe every sample came back as frame 0.
- **Pad frames to a square**, don't crop: the motion often happens at the edges.
- **Store the time in the filename** (`vframe_<clip>_<ms>.jpg`) or in your database, so you can rebuild the sequence later.

### The interleaving workaround

`vlm.cpp:846-856` joins all text parts of a message and puts every media marker first. A message of `Video:`, frame, `[0m0.00s]`, frame … becomes 16 markers followed by `Video:[0m0.00s][0m5.00s]question`. The timestamps no longer sit next to their frames. The fix that worked:

1. Template the conversation with a placeholder text (for example `@@GENIEX_MEDIA_TURN@@0@@…`) in place of each media turn.
2. Replace each placeholder in `formattedText` with the interleaved string of text and `<__media__>` markers.
3. Pass only that turn's image paths, in marker order, to `injectMediaPathsToConfig`.
4. On later turns, re-splice every earlier placeholder the same way, so history renders identically and the incremental prompt cache stays aligned.

### Results and limits

A 16-frame, 8 s clip costs 4,178 prompt tokens and 7.4 s to the first word (5.4 s of that is frame encoding), then 39 tok/s. The model described motion correctly. On "when does the square turn red" (answer: 5 s), it was right in 1 of 3 runs with the model card's sampling and wrong in 2 of 2 with the defaults. Treat exact timing as unreliable at 2B; store your own frame timestamps and let the model describe what is in each segment.

## Architecture for a photo and video library app

Analyze each photo and each video shot once, in the background, into structured JSON stored in SQLite. Then search runs on the database and never waits on the model.

&#91;embedded content: media library pipeline · 6 stages\]

Only the model stage touches the NPU. Everything after it is ordinary database work, so search stays instant even while analysis is still catching up on a large gallery.

### Design decisions

- **Unit of analysis = a shot, not a whole video.** Split videos into shots by comparing small downscaled frames (a big histogram change marks a cut), then analyze each shot with up to 16 frames at 2 fps. Each database row then maps straight to an editor's in and out points.
- **One single-turn call per item, with `reset()` between.** This avoids history drift and keeps the prompt cache simple. Use a fixed system prompt that asks for one JSON object.
- **Force JSON with a grammar.** `SamplerConfig.grammarString` accepts a GBNF grammar, and `applyChatTemplate` can return one for tool schemas. Untested in the demo; verify it first.
- **Cap replies** at about 400 tokens per item and retry once on `stopReason = length` or invalid JSON.
- **Key everything by a content hash** of the file, so renames and duplicates don't trigger re-analysis. Store `model_id` and `prompt_version` on every analysis, so a better prompt or model can re-run only stale rows.
- **Context:** 16K is plenty for one shot (a 16-frame shot is about 4,200 tokens). Don't load 256K in a background worker; it closed seven other apps.
- **Search:** SQLite FTS5 over captions and tags gives instant text search. Optionally, LFM3-1.7B can turn a typed request ("wide shots of the beach at sunset") into filters plus an FTS query.

### Starting schema

| Table | Key columns |
| --- | --- |
| `media` | `id`, `content_hash`, `uri`, `kind` (photo / video), `taken_at`, `duration_ms`, `width`, `height`, `gps` |
| `shot` | `id`, `media_id`, `start_ms`, `end_ms`, `keyframe_path` (photos get one shot covering the whole item) |
| `analysis` | `shot_id`, `caption`, `subjects`, `setting`, `shot_type` (wide / medium / close), `camera_motion`, `quality_notes`, `raw_json`, `model_id`, `prompt_version`, `analyzed_at` |
| `tag` / `shot_tag` | normalized tags with a confidence column |
| `analysis_fts` | FTS5 virtual table over `caption`, `subjects`, `setting` and tags |

### Throughput estimate

These are estimates from the demo's measured speeds, not benchmarks. A photo costs about 0.6 s plus about 300 output tokens at 45 tok/s, around 7 s in all. A 16-frame shot costs about 7.4 s plus 8 s of output, around 15 s. So 1,000 photos take about 2 hours and 100 shots about 25 minutes, which fits an overnight charging window. Measure on real footage before promising anything in the UI.

## Testing and debugging on device

Drive the phone over USB with `adb`, and read the answer from logcat, not the screen. Turn on Do Not Disturb first: a notification banner moved the screen under a scripted tap and broke one test run.

| Task | Command |
| --- | --- |
| Install over the old build | `adb install -r -t build/outputs/apk/debug/app-debug.apk` |
| Cold start | `adb shell am force-stop <pkg>; adb shell am start -n <pkg>/<activity>` |
| Find a button | `adb shell uiautomator dump /sdcard/ui.xml` then grep `resource-id` and `bounds` |
| Tap / type | `adb shell input tap X Y`; `adb shell input text "Hello%sworld"` (`%s` = space) |
| Clear a text field | `adb shell input keycombination 113 29` (Ctrl+A), then `KEYCODE_DEL` |
| Native crash | `adb logcat -d -b crash` for the backtrace; grep the main log for `GGML_ASSERT` to get the message |
| Prompt as sent | log `formattedText` before `generateStreamFlow` |
| NPU memory | sum `rpcmem_dma_alloc … size N` lines during load |
| Free RAM | `adb shell grep MemAvailable /proc/meminfo`; watch `lmkd` lines for background apps killed |
| App files | `adb shell run-as <pkg> ls files/geniex/models/…` (debug builds only) |
| Test media | make clips with `ffmpeg -f lavfi`, `adb push` to `/sdcard/Movies`, then `MEDIA_SCANNER_SCAN_FILE` |

On Git Bash, set `MSYS_NO_PATHCONV=1`, or paths like `/sdcard/ui.xml` get rewritten to `C:/Program Files/Git/sdcard/ui.xml`.

Build test clips whose answer can only come from what you're testing. The timing test used a square that turns red at exactly 5 s with no on-screen clock, so only the timestamps could reveal when. An early clip used `drawbox x='t*100'`, which ffmpeg doesn't animate. Extract a frame and look at it before trusting a clip.

## Gotchas checklist

Check each of these when you start the new app; every one cost time in the demo.

- [ ] Depend on `geniex-android:0.10.0` and port the 0.3.x API changes (`enable_thinking`, `model_name`, `nPast`).
- [ ] VLM `ModelConfig` uses `nBatch = 2048`, `nUBatch = 512`; never 1.
- [ ] Pass the templated prompt to `generateStreamFlow`, never raw user text.
- [ ] Set `maxTokens` explicitly; 0 means 128 or 512.
- [ ] Set the model card's `SamplerConfig`; use `minP = 1e-6f` for 0.
- [ ] Pass `chipset = "SM8850"` for any `ai-hub-models/*` pull on the S26.
- [ ] The compute-unit dialog defaults to NPU (the sample checked CPU last, so CPU won).
- [ ] Image paths are absolute files, never content URIs.
- [ ] Video frames use `OPTION_CLOSEST`, are padded to a square, and carry their timestamps.
- [ ] Media turns are spliced into `formattedText`, because geniex moves markers ahead of text.
- [ ] Image paths in the config are in the same order as the `<__media__>` markers.
- [ ] `nCtx` fits your memory budget (about 12 KB per token for LFM3), since a 256K load closed seven background apps.
- [ ] Long jobs have a token cap and a retry on `stopReason = length`.
- [ ] Stop and destroy the wrapper before loading another model; only one model fits comfortably at full context.
- [ ] Use Do Not Disturb and `MSYS_NO_PATHCONV=1` while scripting the phone.

## Upstream bugs worth reporting or fixing

Two GenieX bugs are small, confirmed in source, and good pull requests. The sample app needs one PR bundling its fixes, and two issues are design questions for the maintainers.

| # | Repo | Problem | Where | Kind |
| --- | --- | --- | --- | --- |
| 1 | qualcomm/GenieX | Warmup decodes BOS+EOS in one batch without clamping to `n_batch`, so `nBatch = 1` aborts the app instead of failing the load | `sdk/plugins/llama_cpp/src/params.cpp:151` | PR: clamp to `llama_n_batch(ctx)`, as llama.cpp's own warmup does |
| 2 | qualcomm/GenieX | VLM message building joins all text and puts every media marker first, losing interleaved order | `sdk/plugins/llama_cpp/src/vlm.cpp:846` | PR: walk the parts in order, marker for media, text for text |
| 3 | qualcomm/GenieX | Android install docs pin `geniex-android:0.3.1` (README says 0.4.0) | `docs/en/run/android/install.mdx:49`, `docs/cn/…`, `README*.md` | PR: bump to 0.10.0 |
| 4 | qualcomm/ai-hub-apps | `geniex_chat_android` pins 0.3.5, crashes VLMs on 0.10.0 (`nBatch = 1`), defaults the load dialog to CPU, says `maxTokens 0 = no limit`, targets `SM8750` only | `geniex_chat_android/` | PR: bump SDK, port API, fix all four, add LFM3 models |
| 5 | qualcomm/GenieX | `SamplerConfig` treats 0 as "default", so `min_p = 0` and `top_p = 0` cannot be requested | `params.cpp:208`, `SamplerConfig.kt` | Issue: needs an API decision (nullable fields or a sentinel) |
| 6 | qualcomm/GenieX | No video input even though LFM3-VL supports it; the Maven sources JAR for 0.10.0 holds only a README | C API `geniex.h`; Maven | Issues: feature request, packaging |

PRs 1 and 2 also fix things for everyone using the CLI and Python SDK, since the plugin is shared. Check `qualcomm/GenieX` for an existing issue or PR on each item before opening anything new.
