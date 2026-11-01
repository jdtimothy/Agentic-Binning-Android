# Plan 2a spike: text models and the Claude SDK on the phone

Oct 30, 2026 · Galaxy S26 Ultra, geniex-android 0.10.0. Task 1 of the Plan 2a
plan. The scratch code (an instrumented `SpikeTest`) was thrown away; this file keeps the numbers.

## Summary

| Question | Answer |
| --- | --- |
| Free space before the spike | `/data` 459G, 440G used, **19G free** (96%) |
| LFM3-1.7B grammar | **Holds** every time (valid JSON of the reply shape, `eos`). Sorting quality is the limit, not the grammar: use chunks of about 10 items |
| Qwen3-8B route | **GGUF on `llama_cpp`**: `bartowski/Qwen_Qwen3-8B-GGUF`, precision `Q4_0`. No AI Hub bundle exists for the SM8850 |
| Qwen3-8B context | **8192** (16384 loads but leaves about 0.5 GB free; 32768 does not fit) |
| Qwen3-8B grammar | **Honoured**; 40 of 40 ids, sensible broad categories |
| `enableThinking = false` | Suppresses `<think>` on Qwen3-8B and LFM3-1.7B |
| Qwen3-4B `qairt` (Task 1b) | **Pulls, but does not load.** `qualcomm/Qwen3-4B`, hub `AIHUB`, chipset `qualcomm-snapdragon-8-elite-gen5-for-galaxy` (2.9 GB, 124 s; `SM8850` gives rc -100015). Fixed context **4096**. QNN can't map the fourth context binary on the NSP, and the low-memory killer then ends the app at about 6.6 GB of DMA buffers (3 of 3 attempts). Grammar on `qairt` is untested |
| Qwen3-4B `qairt`, second attempt (Task 1c) | **Still does not load; skip it.** Same failure with geniex 0.9.1 (the version AI Hub benchmarked) and without `mem_type`; `nCtx` is refused. GenieX's `qairt` engine loads all 10 graphs per part (prefill and decode at 5 context lengths) with their own DMA buffers, so it needs about 2.2 times the bundle size: about 7 GB for this 3.2 GB bundle, more than an app may hold on this 12 GB phone. `Qwen3-4B-Instruct-2507` has the same layout. **`grammarString` is not supported on `qairt`**: generate throws and the flow never completes |
| Qwen3-4B GGUF fallback (Task 1b) | `bartowski/Qwen_Qwen3-4B-GGUF` `Q4_0` on `llama_cpp`: **works**. 8192 context, 3.8 GB NPU, 15 tok/s decode, grammar honoured, 40 of 40 ids in 65 s. **Not the `qairt` build the user asked for** |
| Anthropic Java SDK on Android | **Works.** Invalid key gives a 401 in 0.6 s. Needs one packaging exclude. The 401 class is `UnauthorizedException`, not `AuthenticationException` |

## LFM3-1.7B (`LiquidAI/LFM3-1.7B-GGUF`, Q4_0, `llama_cpp`, NPU)

Pulled with `ModelDownloader` in 36 s (`LFM3-1.7B-QAD-Q4_0.gguf`, `tokenizer_path` null).

| Measure | Value |
| --- | --- |
| Load, `nCtx = 32768` | 1.6–3.8 s (2.0 s on the first load) |
| NPU memory (sum of `rpcmem_dma_alloc`) | 1,716 MiB at 32768 |
| `MemAvailable` drop at load | about 1.9 GB |
| `dumpsys meminfo` TOTAL PSS | 380–416 MB (excludes the NPU buffers) |
| 40 items, prompt | 3,256 tokens (13,388 templated chars, 4.1 chars per token) |
| Prefill | 2,950–3,250 tok/s (time to first token 1.1 s at 3.3K tokens) |
| Decode | 30–44 tok/s |
| 40-item reply | 587 tokens in 16 s (free-form grammar), 1,142 tokens in 30 s (per-chunk id grammar) |

Grammar results (all replies parsed, all `stopReason = eos`):

| Run | Result |
| --- | --- |
| 40 items, grammar of the reply shape | Valid JSON, but 24 "categories" that copy item names, only 5 of 40 assignments, and id 123 copied from the prompt's example |
| 40 items, per-chunk grammar (ids fixed, in order; at most 12 new categories) | 40 of 40 ids, but most items got their own category name |
| 40 items, two passes (propose, then assign with `category` as an enum) | Structurally perfect; the proposals copy the first 12 item names and assignments look random |
| 20 items, per-chunk grammar | Odd categories ("Man", "Moonlit"), 3 names not in its own list |
| **10 items, per-chunk grammar** | **4 sensible broad categories (Food, Sailboats, Nature, Windsurfing), 3 assignments to unlisted names** |
| 10 items with 5 existing categories | Re-lists existing ones as new (normalization merges these), and reasoning text leaked into a `category` string |

Takeaways for Tasks 5 and 6:
- Build the local grammar **per chunk**: the assignment list is exactly the chunk's ids, in order, so ids are always present, unique and in the chunk. This removed every id error.
- Bound the free strings in the local grammar (category at most 40 chars, name at most 60), so reasoning can't leak into a field. Prefer `category ::= <existing names> | <bounded new name>`.
- Bound `ws` (`[ \t\n]{0,4}`); unbounded whitespace is a loop risk.
- The validator's unknown-category rejection and retry will fire often on LFM; expect quality to be "best effort".

## Qwen3-8B

### AI Hub `qairt`: not available

- `ModelManagerWrapper.listHubModels(null)` lists `qualcomm/Qwen3-8B` (LLM) with chipset `qualcomm-snapdragon-8-elite-gen5-for-galaxy` among others. `listHubModels("SM8850")` returns nothing; that filter wants the long names.
- Pulling `ai-hub-models/Qwen3-8B` with `chipset` `SM8850`, `SM8850-AD` or `qualcomm-snapdragon-8-elite-gen5-for-galaxy` fails at once with rc `-100015` (`GENIEX_ERROR_COMMON_CHIPSET_UNAVAILABLE`).
- The cause: AI Hub's `release-assets.json` for `qwen3_8b` (v0.65.0) has w4a16 assets only for `qcs8275`, `qcs9075`, `sa8775p`, X Elite, X2 Elite and X Plus. Qwen3-4B, by contrast, has `qualcomm-snapdragon-8-elite-gen5-for-galaxy` assets. The hub listing overstates Qwen3-8B's phone support.

### GGUF on `llama_cpp`: works

- `Qwen/Qwen3-8B-GGUF` (official) has no Q4_0 (Q4_K_M, Q5_0, Q5_K_M, Q6_K, Q8_0); `unsloth/Qwen3-8B-GGUF` has none either. `bartowski/Qwen_Qwen3-8B-GGUF` has `Qwen_Qwen3-8B-Q4_0.gguf` (4,787,332,640 bytes), quantized from the official weights. Q4_0 is the format the Hexagon backend runs.
- Pulled with `ModelPullInput(model_name = "bartowski/Qwen_Qwen3-8B-GGUF", precision = "Q4_0", hub = HUGGINGFACE)` in 161 s. `getPaths`: `runtime_id = llama_cpp`, `model_name = Qwen_Qwen3-8B`, `tokenizer_path` null.

| Measure | `nCtx = 8192` | `nCtx = 16384` |
| --- | --- | --- |
| Load | 7.6 s | 9.5 s |
| NPU memory | 5,770 MiB | 6,932 MiB |
| `MemAvailable` before → after load | 3.46 GB → 0.59 GB | 5.19 GB → 0.55 GB |
| TOTAL PSS / swap PSS | 433 MB / 405 MB | 432 MB / 411 MB |

- The cache costs about 145 KB per token (36 layers × 8 KV heads × 128 × 2 × 2 bytes), matching the 1,162 MiB step from 8K to 16K. 32768 would need about 9.3 GiB and was not attempted.
- Android's low-memory killer reaped about 680 background processes over the 2.5-minute 40-item run at 8192. Nothing of ours crashed, but other apps are evicted for as long as Qwen is loaded.
- 40 items with the reply-shape grammar: prompt 3,219 tokens at 648 tok/s (time to first token 5.0 s), 1,110 tokens at **7.9 tok/s**, 146 s in all, `eos`. Grammar honoured; 40 of 40 ids, no duplicates or foreign ids; 5 categories (Nature 10, Ocean 21, People 4, Events 4, Food 1). One clear mistake: "Breakfast Plate" in Nature.
- `enableThinking = false`: the template ends with `<think>\n\n</think>\n\n` and the reply has no `<think>`. With `true`, the reply starts with a `<think>` block (188 tokens for a one-line question).

## Anthropic Java SDK (`com.anthropic:anthropic-java:2.34.0`)

- **Packaging:** `assembleDebug` fails on `META-INF/DEPENDENCIES` (httpclient5, httpcore5, httpcore5-h2). This alone fixes it:
  ```groovy
  packaging {
      jniLibs.useLegacyPackaging = true
      resources.excludes += ['META-INF/DEPENDENCIES']
  }
  ```
- **What it pulls in:** OkHttp 4.12.0, Jackson 2.18.2 (databind, jdk8, jsr310, kotlin module), kotlin-reflect 1.8.10, Apache httpclient5 5.3.1 and httpcore5 5.2.4, slf4j-api 2.0.16, victools jsonschema-generator 4.38.0.
- **APK size (debug, no R8):** 138,309,351 → 160,414,104 bytes (+22.1 MB, +16%), both built fresh from the same source, with and without the dependency; the dex grows from 28.7 MB to 51.7 MB.
- **On the phone, key `sk-ant-invalid`, `model("claude-sonnet-5-5")`, `maxTokens(16)`:** `com.anthropic.errors.UnauthorizedException`, `statusCode()` 401, `errorType()` `authentication_error`, after 596 ms (117 ms on the second call). TLS, OkHttp and Jackson work.
- **Structured output:** `OutputConfig.builder().format(JsonOutputFormat.builder().schema(schema).build()).build()` builds, with `JsonOutputFormat.Schema.builder().putAdditionalProperty(key, JsonValue.from(map))`. A request carrying it serialised and got the same 401.
- **Exception names for Task 6's mapping** (package `com.anthropic.errors`): `BadRequestException` 400, `UnauthorizedException` 401, `PermissionDeniedException` 403, `NotFoundException` 404, `UnprocessableEntityException` 422, `RateLimitException` 429, `InternalServerException` 5xx, `UnexpectedStatusCodeException`, `AnthropicIoException`, `AnthropicRetryableException`, `AnthropicInvalidDataException`, `SseException`; all HTTP ones extend `AnthropicServiceException`. There is no `AuthenticationException`.

## Qwen3-4B (Task 1b)

Oct 31, 2026. The user picked AI Hub's `qairt` build of Qwen3-4B as the third sorting option. Same
method as Task 1: an instrumented scratch `SpikeTest`, run one method at a time, with logcat captured to a file. Only the
test APK was installed.

### `qairt`: pulls, but does not load

- `listHubModels(null)` lists `qualcomm/Qwen3-4B` (LLM) with `qualcomm-snapdragon-8-elite-gen5-for-galaxy` among its chipsets
  (also `qualcomm/Qwen3-4B-Instruct-2507`, `-0.6B`, `-1.7B`, `-8B`). `listHubModels("SM8850")` still returns nothing.
- `ModelPullInput("qualcomm/Qwen3-4B", precision = "", hub = AIHUB, chipset = "SM8850")` fails in 0.36 s with rc `-100015`
  (`GENIEX_ERROR_COMMON_CHIPSET_UNAVAILABLE`). With **`chipset = "qualcomm-snapdragon-8-elite-gen5-for-galaxy"`** the pull
  succeeds in 124 s. The bundle's `metadata.json` names `sm8850-ad` as an alias, but GenieX only accepts the long name.
  (`Instructions.md` still says `SM8850`, which is wrong here.)
- Bundle (2.9 GB): `part1_of_4.bin` to `part4_of_4.bin` (778, 666, 666 and 1,065 MB), `genie_config.json`,
  `tokenizer.json`, `metadata.json` and others. `getPaths`: `runtime_id = qairt`, `model_name = qwen3_4b`,
  `model_path = .../part3_of_4.bin`, `tokenizer_path` null. Precision w4a16, built with QAIRT 2.50.0.
- **Fixed context: 4096.** `genie_config.json` has `"context": {"size": 4096}` and `"max-num-tokens": 2048`. The metadata
  lists `context_lengths` 512 to 4096, and the KV inputs are `[8, 1, 128, 4095]` uint8. `supports_thinking` is true, and the
  sample prompt ends with an empty `<think>\n\n</think>` block, like the `enableThinking = false` template.
- **Load** (`LlmCreateInput(model_path, null, ModelConfig(nCtx = 0, nGpuLayers = 0), "qairt", "npu")`) failed all 3 times,
  with `MemAvailable` at 3.2, 3.5 and 6.0 GB beforehand:
  1. Contexts 1 to 3 load on DSP process (`pdId`) 2. For context 4, `fastrpc_mmap_validate` fails on the DSP (`0x80000583`),
     followed by `QnnDsp <E> Failed to map input buffer on NSP`, `ERROR happen when trying to allocate buffers for graph
     token_ar1_cl4096_4_of_4`, and `Context create from binary failed ... for context 4, err 1002`.
  2. QNN then retries context 4 on another DSP process and allocates its buffers again. In all 3 runs the rpcmem
     allocations added up to exactly 8,336 MiB.
  3. About 11 s after `create`, `lmkd` reclaims our own foreground process (`oom_score_adj 0`, `dmabuf_rss 6,625,432 kB`,
     "min watermark is breached and swap is low"). `build()` never returns, so the app would die with it.
  Since the result is the same with 6 GB free, the cause looks like the DSP address space for this bundle, not free RAM.
  The bundle is compiled for both prefill and decode at every context length (512 to 4096), which may account for it.
  A later geniex or bundle may fix this. With 0.10.0 on this phone, `qairt` Qwen3-4B is not usable.
- Each attempt made `lmkd` reap 140 to 360 background processes.
- So for `qairt` the grammar support, `enableThinking`, speed and category quality are **unmeasured**. If a later
  bundle loads, its budget would be `ChunkBudget(4096, 20, 100)`: at 85% of 4096 tokens, 23 assignment items and about 110
  proposal lines fit. Without a grammar measurement, Task 6 should assume `qairt` ignores `grammarString` and rely on the
  validator and retry.

### GGUF on `llama_cpp` (the Task 1 route): works, but it is not the `qairt` build

- `Qwen/Qwen3-4B-GGUF` (official) has only `Q4_K_M` at 4 bits, and unsloth and bartowski both have `Q4_0`. Pulled
  `ModelPullInput("bartowski/Qwen_Qwen3-4B-GGUF", "Q4_0", HUGGINGFACE)`: `Qwen_Qwen3-4B-Q4_0.gguf`, 2,375,773,120 bytes, in
  59 s. `getPaths`: `runtime_id = llama_cpp`, `model_name = Qwen_Qwen3-4B`, `tokenizer_path` null.
- The KV geometry is the same as Qwen3-8B (36 layers, 8 KV heads, 128 dims), so the cache costs the same 145 KB per token.

| Measure | `nCtx = 8192` | `nCtx = 16384` |
| --- | --- | --- |
| Load | 3.2-3.8 s | 10.9 s (it swapped) |
| NPU memory (sum of `rpcmem_dma_alloc`) | 3,776 MiB | 4,938 MiB |
| `MemAvailable` before -> after load | 4.82 -> 0.81, 5.36 -> 1.83, 5.75 -> 1.83 GB | 4.17 -> 0.96 GB |
| TOTAL PSS after load | 425-462 MB | 404 MB (200 MB swap PSS) |
| `lmkd` reaps per run | 0-41 | 49 |

Runs at 8192 (temperature 0.1, `enableThinking = false`, every stop `eos`):

| Run | Prompt | Prefill | Decode | Generated | Wall | Result |
| --- | --- | --- | --- | --- | --- | --- |
| 40 items, reply-shape grammar | 3,219 tok | 1,141 tok/s (ttft 2.8 s) | 15.0 tok/s | 628 | 45 s | Valid JSON, but **only 20 of 40 assigned** (ids 1 to 20 missing); 10 categories, 3 unused |
| **40 items, per-chunk id grammar + hint** | 3,261 | 1,139 (ttft 2.9 s) | 14.5 | 901 | **65 s** | **40 of 40**, no duplicate or foreign ids; 4 categories: Nature 12, Coastal 16, Ocean 7, Beach 5 |
| 20 items, per-chunk id grammar + hint | 1,868 | 1,290 (ttft 1.5 s) | 16.5 | 482 | 31 s | 20 of 20; 4 categories: Nature 5, Ocean 9, Sunset 3, Beach 3 |

- **Grammar is honoured** on `llama_cpp`, as with the other GGUFs. As with LFM, the per-chunk id grammar is needed: with only
  the reply shape, the 4B skips items.
- **`enableThinking = false` works**: the template ends `<think>\n\n</think>\n\n`, and the reply "A ripe banana is
  yellow" (6 tokens) has no `<think>`. With `true`, the reply opens with 162-218 tokens of reasoning. Decode is 20-23 tok/s
  for short replies.
- **Category quality** is between LFM3-1.7B and Qwen3-8B. Item names are good (for example "Sails at Sunset", "Tall Tree Trunk",
  "Wedding Ceremony"). With the hint it picks only 4 broad scenery categories, so items that don't fit them get forced in:
  Breakfast Plate, Man giving presentation and Fire dancers go to Nature, Wedding Ceremony to Beach, Forest Stream
  Waterfall to Ocean or Coastal. Chunks of 20 aren't better than 40. Qwen3-8B on the same 40 items made one such
  mistake. The categories are usable, but expect more corrections than with the 8B.

## Qwen3-4B qairt, second attempt (Task 1c)

Oct 31, 2026. A second attempt to load `qualcomm/Qwen3-4B` (`qairt`) and find out why it fails. Same method: an instrumented
scratch `SpikeTest` in a separate worktree, one `am instrument` run at a time, with logcat captured to a file. The bundle
from Task 1b was reused. Its config files were changed only for one test, then restored and checked by md5.

**Result: it can't be made to load with GenieX on this phone. Skip it for now.**

### What was tested

| Hypothesis | Test | Result |
| --- | --- | --- |
| Our parameters differ from the sample's | Compared with `docs/reference/sample/MainActivity.kt.txt` | Same: `ModelConfig(nCtx = 0, nGpuLayers = 0)`, `runtime_id` from `getPaths` (`qairt`), `compute_unit = "npu"`, `tokenizer_path` from `getPaths` (null) |
| `nCtx` picks a smaller context and fewer graphs | `nCtx = 1024` | Refused at once: `--nctx (n_ctx) is not supported by the qairt plugin` (rc -100016) |
| Something else in our process holds memory | Task 1b evidence plus these runs | No. Each run is a fresh process with 64 MB PSS before load and no other model loaded |
| geniex 0.10.0 regressed (AI Hub benchmarked this bundle on the S26 with **geniex v0.9.1**) | Built the worktree against `geniex-android:0.9.1` | **Same failure**: the same 4th context fails to map on pd 2, then `lmkd` kills the app at 6.59 GB `dmabuf_rss` |
| A newer geniex | Maven Central `maven-metadata.xml` | **0.10.0 is the latest** (Oct 28) |
| The `shared_buffer` `mem_type` in `htp_backend_ext_config.json` | Removed the `memory` section | Same allocations and failure (kill at 6.56 GB) |
| Bundle config the runtime should be pointed at | Strings in `libgeniex_core.so` | GenieX reads `genie_config.json` (`ctx-bins`, sampler, tokenizer) and parts of `htp_backend_ext_config.json`. It does not parse `use-mmap`, `mmap-budget` or `spill-fill-bufsize`, and `metadata.json`'s `context_lengths` is not used. The bundle has no `libGenie`; GenieX runs its own engine on the QNN C API |
| Another variant | Read the zips' central directories and `metadata.json` from AI Hub over HTTP range requests (nothing downloaded) | The `genie` and `geniex_qairt` zips for Qwen3-4B on this chipset contain the same `.bin` files (byte sizes identical). **`Qwen3-4B-Instruct-2507`** has the same layout: 4 parts (778/666/666/1,065 MB), context lengths 512 to 4096. It would fail the same way, so it was not pulled |
| `qairt` itself works in our app | Pulled `qualcomm/Qwen3-1.7B` (`qairt`, 1.75 GB of `.bin`, 48 s) as a control | **Loads in 5.5 s** and generates (see below) |

### Root cause

GenieX's `qairt` engine loads **every graph in every part**: prefill (AR-128) and decode (AR-1) at all 5 context lengths,
so 10 graphs per transformer part. Each graph gets its **own** DMA input buffers, sized for its context length, plus 32 MiB
scratch buffers. Per transformer part of the 4B, that is 9.8 + 21.9 + 46.2 + 70.4 + 94.7 MiB of prefill KV inputs and
12 + 24 + 48 + 72 + 96 MiB of decode KV inputs (about 495 MiB, where one 96 MiB KV would do), on top of the weights. The
weights and graph data of a 666 MB part take about 1.1 GB of DMA (588 + 514 MiB), and part 4 takes 1.6 GB (960 + 698 MiB).

- **The 1.7B control needs 3.9 GB of live DMA for 1.75 GB of `.bin` files** (peak 4.4 GB), about 2.2 times, with the same
  per-context-length buffer pattern (8.2 to 79.0 MiB prefill, 10 to 80 MiB decode, per part).
- **The 4B therefore needs about 7 GB.** At about 6.5 GB of live DMA, the fourth part's buffers no longer fit in the third
  DSP process's address space (`fastrpc_mmap_validate` 0x80000583, `Failed to map input buffer on NSP`, graph
  `token_ar1_cl4096_4_of_4`), and QNN retries on a fourth DSP process, allocating again. Within about a second, `lmkd`
  reclaims our app (`oom_score_adj 0`, 6.56 to 6.63 GB `dmabuf_rss`, "min watermark is breached and swap is low").
- The phone has 11.35 GB of RAM (`MemTotal`), and `MemAvailable` was 4.3 to 6.0 GB before the loads. DMA buffers can't be
  swapped, and a foreground app can't raise its own priority, so the app can't get 7 GB.
- AI Hub's S26 numbers for this bundle (15 to 23 tok/s, geniex v0.9.1) presumably come from a test harness on a clean device,
  not from an app.

Nothing on the app side changes the allocation: GenieX exposes no option to load fewer context lengths or share the I/O
buffers. A fix needs either a GenieX release that loads one context length or shares the KV buffers across graphs, or an AI
Hub bundle compiled with a single context length.

### Qwen3-1.7B `qairt` control (measured, not proposed for the catalog)

- `ModelPullInput("qualcomm/Qwen3-1.7B", "", AIHUB, chipset = "qualcomm-snapdragon-8-elite-gen5-for-galaxy")`. `getPaths`:
  `runtime_id = qairt`, `model_name = qwen3_1_7b`, `model_path = .../part2_of_4.bin`, `tokenizer_path` null.
- Load 5.5 s. `MemAvailable` 5.83 to 3.05 GB. Live DMA 3.9 GB. PSS 143 MB after load.
- 36-token prompt: prefill 630 to 895 tok/s (ttft 40 to 57 ms), decode 27 to 30 tok/s, `eos`.
- `enableThinking = false` works: GenieX's `qairt` chat template ends `<think>\n\n</think>\n\n`, and the reply ("A ripe
  banana is yellow.", 7 tokens) has no `<think>`.
- **`grammarString` is not supported on `qairt`.** With a grammar set, `generate` logs `[Llm JNI] generate() unknown
  exception` at once. The flow emits neither `Completed` nor `Error`, so a collector waits forever (the test needed a
  timeout and `stopStream`). Passing `tokenizer_path = <model_dir>/tokenizer.json` doesn't change this. Any future `qairt`
  backend must not send a grammar, and must time out generation.

## Recommended budgets

Measured on 75 real item lines (Task 5's `itemLine` format): 269 chars per line on average, about 77 tokens by the plan's `chars / 3.5` estimate. Both tokenizers gave about 4.1 chars per token on this text, so the estimate runs about 17% high, which is safe. Replies came to about 26–28 tokens per item, close to the plan's 30.

Items that fit 85% of the context with the plan's estimator (system prompt about 270 tokens, 20 categories about 460, reply 30 per item plus 200):

| `nCtx` | Assignment items | Proposal items (name and tags only) |
| --- | --- | --- |
| 8192 | 56 | 265 |
| 16384 | 121 | 565 |
| 32768 | 251 | 1,167 |

| Backend | `nCtx` | `ChunkBudget(contextTokens, maxChunk, proposeSample)` | Why |
| --- | --- | --- | --- |
| Claude | n/a | `ChunkBudget(200_000, 200, 2000)` (plan default) | Not measured against the model (no key); 200 items is about 21K prompt tokens and 6.6K reply tokens |
| LFM3-1.7B | **16384** | `ChunkBudget(16384, 10, 20)` | Quality falls off above about 10 items; at 10 a chunk is about 1.2K prompt tokens and 10 s. 16384 halves the cache of 32768 (12 KB per token) |
| Qwen3-8B | **8192** | `ChunkBudget(8192, 40, 200)` | 40 items take about 2.5 min at 7.9 tok/s; 56 would fit. 16384 leaves too little free memory |
| Qwen3-4B `qairt` | 4096 (fixed) | `ChunkBudget(4096, 20, 100)` | **Does not load** with geniex 0.10.0; budget from the fit arithmetic only (23 items fit 85% of 4096) |
| Qwen3-4B GGUF (fallback) | **8192** | `ChunkBudget(8192, 40, 200)` | 40 items in about 65 s at 15 tok/s with the per-chunk grammar; 3.8 GB NPU, about 1.8 GB left free. 16384 costs 1.2 GB more for no gain at 40 items |

For a 1,600-item first run: Qwen3-8B needs 40 chunks, about 1.6 hours; Qwen3-4B (GGUF) 40 chunks, about 45 minutes; LFM3-1.7B 160 chunks, about 30 minutes.
