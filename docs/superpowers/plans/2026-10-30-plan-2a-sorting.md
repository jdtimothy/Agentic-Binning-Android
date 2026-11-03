# Plan 2a: Sorting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After each analysis run, a sorting model (Claude, LFM3-1.7B or Qwen3-4B, chosen in Settings) puts every newly analyzed item into a category and gives it a friendly name, with only one model loaded at a time. Add a "max frames per clip" setting for video analysis.

**Architecture:**
- The pipeline controller stays the single owner of model work. It runs analysis and then a new **Categorizing** phase in the same run coroutine, through a small `ModelArbiter` that Plan 2b's Re-sort and Plan 3's Bin Files will also use.
- Sorting is split into three parts:
  - a pure **sorting contract**: prompt, JSON schema and grammar, parser, validator and chunking, all unit-tested on the JVM;
  - three `TextModel` backends behind one interface;
  - a `Categorizer` that applies validated results to the project database, one transaction per chunk.
- Settings gains the sorting model choice, a Keystore-encrypted Claude API key, and the frame cap.

**Tech Stack:** As Plan 1, plus `com.anthropic:anthropic-java:2.34.0` (Claude Messages API, structured outputs) and the GenieX `LlmWrapper` for on-device text models.

**Spec:** `docs/superpowers/specs/2026-10-28-agentic-binning-design.md`, sections "Settings", "Pipeline → 3. Categorize" and "Models". Read the Plan 2 notes too: `docs/superpowers/plan-2-notes.md`, and `Instructions.md` before touching GenieX code.

**Decided since the spec was approved (user, 2026-10-30):**
- Plan 2 is split. 2a (this plan) covers sorting. 2b covers the Project Media screen, editing and Re-sort, with mockups approved first.
- Video frames become a Settings cap, "max frames per clip". It is never faster than 2 fps, and the context window is sized automatically from it. Shot splitting gets its own later plan; the spec's non-goal stands for now.
- DNG photos are ordinary items (already merged).

## Amendments after Task 1 (user, 2026-10-30)

- **Qwen3-4B replaces Qwen3-8B** as the third sorting option. Use Qualcomm AI Hub's `qairt` build for this chip (https://aihub.qualcomm.com/models/qwen3_4b). Qwen3-8B exists only as a 4.8 GB GGUF here, and Android evicted background apps while it was loaded. Task 1b measures Qwen3-4B before Task 6 builds its backend.
- **Default sorting model:** LFM3-1.7B until the user first saves a Claude API key. Saving a key switches the choice to Claude, unless the user has already picked a model themselves. LFM3-1.7B sorts in chunks of about 10 items (spike).
- **Budgets and grammar:** see `docs/superpowers/notes/2026-10-30-plan-2a-spike.md`. The local grammar is built per chunk.

### Task 1b: Spike — Qwen3-4B `qairt` on the phone

- **Pull** with `hub = AI Hub`, the repo id that `ModelManagerWrapper.listHubModels` gives for Qwen3-4B, and the chipset string it accepts for the S26. Try `SM8850` first, then `qualcomm-snapdragon-8-elite-gen5-for-galaxy`.
- **Load** with `nCtx = 0` and `nGpuLayers = 0`, and record the fixed context the bundle reports.
- **Measure** on the spike's 40 item lines (or 10 if the context is small): load time, memory (MemAvailable drop, `rpcmem` sum), prefill and decode tok/s, whether `grammarString` is honoured on `qairt`, whether `enableThinking = false` works, the category quality, and whether Android kills background apps while it is loaded (count `lowmemorykiller` lines in logcat).
- **Record** the results in the spike notes, with a `ChunkBudget` for it, and replace `ModelCatalog.QWEN3_8B` with `QWEN3_4B`. If `qairt` doesn't honour grammars, say so: Task 6 then relies on the validator and retry for it.

## Global Constraints

- All of Plan 1's Global Constraints still hold. Device: S26 Ultra; GenieX 0.10.0; same build pins; build from Git Bash with `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot"`. Never run `connectedDebugAndroidTest` (it deletes the models): run single device tests with `adb shell am instrument -w -e class <Class>#<method> com.agentic.binning.test/androidx.test.runner.AndroidJUnitRunner`.
- **One model loaded at a time.** The vision model is released before a sorting model loads; a sorting model is released in `finally` before anything else loads.
- **Claude sees text only:** ids, kinds, descriptions, actions, tags, suggested names, and category names, descriptions and counts. Never images, frames, file paths or file names.
- **Files are not moved in Plan 2a.** Sorting changes only the database.
- Never overwrite what the user set: items with `nameSetByUser` keep their name, items with `categorySetByUser` keep their category, and categories with `createdBy = "user"` are never renamed, merged or removed by a model.
- Claude: model `claude-sonnet-5-5` (the spec's default), using the official Java SDK. Never raw HTTP or an OpenAI-compatible shim.
  - Structured output through `OutputConfig.builder().format(JsonOutputFormat...)` with the schema from Task 5.
  - Effort `MEDIUM`. Omit `thinking`, `temperature`, `top_p` and `top_k` (they return 400 on this model). `maxTokens` is 16000, non-streaming.
  - Server-side refusal fallbacks are on: `fallbacks: "default"` with beta `server-side-fallback-2026-07-01`. Check `stop_reason` before reading content.
  - Typed SDK exceptions map to reasons, most specific first.
- The API key lives only in the Keystore-encrypted store. It is never logged, never in a URL, and never in an exception message shown to the user. Agents never type a real key: the user enters it on the phone.
- Local text models load with `runtime_id` from `getPaths()` (fallback `"llama_cpp"`), compute unit NPU, `nBatch = 2048`, `nUBatch = 512`, an explicit `maxTokens` and `SamplerConfig`. They use the general preset (LFM3 card: temperature 0.1, topK 50, topP 1.0, minP 1e-6f, repetition 1.1) and `applyChatTemplate(..., enableThinking = false)`. AI Hub `qairt` bundles take `nCtx = 0`, `nGpuLayers = 0` and `chipset = "SM8850"`.
- Schema changes are additive Room migrations (AutoMigration v2 → v3) and never destructive. Commit the exported schema JSON.
- Copy follows the spec's voice: sentence case, no "please", no exclamation marks. Failure reasons are written for people.

## Review Focus

1. **Claude chosen but unusable** (no key, no network, 401, 429, 5xx, a refusal). The run ends normally, items stay Analyzed (uncategorized), the status says why in plain words, and the next run or "Categorize now" retries. Analysis is never marked failed because sorting failed. Test: Task 7 `claudeUnavailable_leavesItemsUncategorized_withReason`.
2. **A model reply that is valid JSON but wrong**: ids missing, duplicated or not in the chunk; unknown categories; an empty name. The validator catches it, retries once with the errors listed, then leaves the chunk's items uncategorized. A bad chunk never writes a partial assignment. Test: Task 5 `validate_rejectsMissingDuplicateForeignIds`.
3. **A huge batch** (1,600 items) on a local model with a fixed context. Chunks keep every call's prompt plus reply inside the context budget, and progress is reported per chunk. Test: Task 5 `chunkPlan_fitsContextFor1600Items`.
4. **Stop, project switch or app kill during Categorizing.** Generation is cancelled, the sorting model is released, committed chunks stay and the rest stay Analyzed. The next run picks up the uncategorized items by status. Test: Task 7 `stopDuringCategorizing_releasesModel_keepsCommittedChunks`.
5. **Near-duplicate categories** ("Sea lion", "sea lions", "Sea-Lions"), including a new one matching an existing or user category. They collapse into one category, the existing or user one winning. Test: Task 5 `normalize_mergesNearDuplicates_intoExisting`.

---

## File Structure

All under `src/main/java/com/agentic/binning/`; tests mirror under `src/test/...` and `src/androidTest/...`.

| File | Responsibility |
| --- | --- |
| `docs/superpowers/notes/2026-10-30-plan-2a-spike.md` | Task 1 findings: text model loads, Qwen3-8B route, Java SDK on Android |
| `db/Entities.kt`, `db/ProjectDatabase.kt` | v3: `sort_run` table |
| `db/SortDao.kt` | Categories, uncategorized items, applying a chunk, FTS refresh, sort runs |
| `settings/SettingsStore.kt` | `sortingModel`, `maxFramesPerClip` |
| `settings/ApiKeyStore.kt` | Keystore AES-GCM encryption of the Claude key |
| `analysis/VideoTiming.kt`, `analysis/VisionBudget.kt` | Frame cap; context size from the cap |
| `sorting/SortPrompt.kt` | System prompt, item and category lines, `SORT_PROMPT_VERSION` |
| `sorting/SortSchema.kt` | JSON schema (Claude) and GBNF grammar (local) for the reply |
| `sorting/SortParser.kt` | Reply JSON → `SortReply` |
| `sorting/SortValidator.kt` | Rules checked in code; name normalization and merging |
| `sorting/ChunkPlanner.kt` | Proposal pass and assignment chunks within a token budget |
| `model/TextModel.kt` | `TextModel` interface, `TextReply`, `SortingUnavailableException` |
| `model/GenieXTextModel.kt` | `LlmWrapper` backend (LFM3-1.7B, Qwen3-4B) |
| `model/ClaudeTextModel.kt` | Anthropic Java SDK backend |
| `model/ModelCatalog.kt` | Qwen3-4B entry (route from Task 1b) |
| `pipeline/ModelArbiter.kt` | One model job at a time, by kind |
| `pipeline/Categorizer.kt` | Replaces `BatchCompleteHandler`: picks uncategorized items, runs chunks, applies results |
| `pipeline/PipelineController.kt` | `Categorizing` phase, `categorizeNow`, sort progress and reason in state |
| `ui/settings/SettingsScreen.kt` | Sorting model picker with download, API key field, frame cap |
| `ui/analyze/AnalyzeMapping.kt` | "Categorizing…" status, uncategorized notice |

---

### Task 1: Spike — text models and the Claude SDK on the phone

Throwaway code on a scratch branch off `plan-2a-sorting`. Only the notes file and any `ModelCatalog` entry are kept. Later tasks build on its findings, so it runs first.

**Files:**
- Create: `docs/superpowers/notes/2026-10-30-plan-2a-spike.md`
- Modify: `model/ModelCatalog.kt` (Qwen3-8B spec, if a route works)

- [ ] **Step 1: Check free space** with `adb shell df -h /data /sdcard` and record it. The phone was 98% full in Plan 1. Qwen3-8B needs about 5 GB. If there isn't room for it plus a 1 GB margin, stop and report back (BLOCKED). The controller asks the user what to free; never delete anything yourself.
- [ ] **Step 2: Measure LFM3-1.7B through `LlmWrapper`** (`ModelCatalog.LFM3_1_7B`; pull it with `ModelDownloader` if absent).
  - Load with `nCtx = 32768` on NPU.
  - Generate once with a GBNF grammar shaped like Task 5's reply, on a hand-written prompt of about 40 item lines (around 4K tokens).
  - Record load time, prefill and decode tok/s, whether the grammar held, and the memory (`adb shell dumpsys meminfo com.agentic.binning`, TOTAL PSS).
- [ ] **Step 3: Find a Qwen3-8B route.** First try an AI Hub `qairt` bundle: find the repo id through GenieX's hub listing or the AI Hub model list, then pull with `chipset = "SM8850"` and load with `nCtx = 0`, `nGpuLayers = 0`. If that fails, try a Qwen3-8B Q4_0 GGUF on `llama_cpp` (the official Qwen GGUF repo, or a repo with a Q4_0 file) at `nCtx = 32768`. For whichever loads, record:
  - the repo id and precision;
  - the context it gives (fixed for `qairt`);
  - whether `grammarString` is honoured;
  - tok/s and memory;
  - whether `enableThinking = false` suppresses `<think>`.

  If neither route works, write that down: Qwen3-8B then stays out of Settings (spec rule).
- [ ] **Step 4: Check the Anthropic Java SDK on Android.**
  1. Add `implementation("com.anthropic:anthropic-java:2.34.0")`. Check `assembleDebug` dexes it; note any `packaging { resources.excludes }` it needs.
  2. On the phone, build a client with a deliberately invalid key, `"sk-ant-invalid"`, and send one `messages().create` with `model("claude-sonnet-5-5")`, `maxTokens(16)`.
  3. Expect an `AuthenticationException` (401). That proves TLS, OkHttp and Jackson work on Android at no cost.
  4. Record the APK size change and whether a `JsonOutputFormat` schema builds.
- [ ] **Step 5: Write the notes file** with the numbers, the chosen Qwen route (or "not available"), the recommended local `nCtx`, and the item-chunk sizes that fit (prompt plus reply ≤ 85% of `nCtx`). Add the Qwen spec to `ModelCatalog` if a route works.
- [ ] **Step 6: Commit** the notes and catalog: `git commit -m "docs: Plan 2a spike results for text models and the Claude SDK"`. Delete the scratch branch.

---

### Task 2: Database v3 — sort runs, category and FTS writes

**Files:**
- Modify: `db/Entities.kt`, `db/ProjectDatabase.kt` (version 3, `AutoMigration(2, 3)`), `db/AnalysisDao.kt`
- Create: `db/SortDao.kt`, `schemas/.../3.json` (generated)
- Test: `src/androidTest/.../db/SortDaoTest.kt`, migration test in the existing migration test class

**Interfaces:**
- Produces:
  - `SortRunEntity(id: Long = 0, startedAt: Long, finishedAt: Long?, modelId: String, promptVersion: String, items: Int, sorted: Int, newCategories: Int, outcome: String /* done | partial | failed | unavailable */, message: String?)`, table `sort_run`.
  - `data class UncategorizedItem(val id: Long, val kind: ItemKind, val description: String, val actionsJson: String, val suggestedName: String, val tags: List<String>, val nameSetByUser: Boolean)`.
  - `data class CategoryInfo(val id: Long, val name: String, val description: String, val createdBy: String, val items: Int)`.
  - `data class Assignment(val itemId: Long, val categoryName: String, val friendlyName: String)`.
- In `SortDao`:
  - `suspend fun categories(): List<CategoryInfo>`
  - `suspend fun uncategorized(): List<UncategorizedItem>`: status Analyzed, `categoryId IS NULL`, ordered by id.
  - `fun observeUncategorizedCount(): Flow<Int>`
  - `@Transaction suspend fun applyChunk(newCategories: List<CategoryEntity>, assignments: List<Assignment>)`:
    1. Inserts the new categories (`createdBy = "model"`, `folderName = null`).
    2. Resolves each assignment's category by exact name.
    3. Sets `categoryId` and `status = Sorted`, and sets `friendlyName` unless `nameSetByUser`.
    4. Rewrites each item's FTS row with its friendly name and category name.

    All of it or nothing.
  - `suspend fun startRun(run: SortRunEntity): Long`
  - `suspend fun finishRun(...)`

**Carried fixes (from `plan-2-notes.md`):**
- `AnalysisDao.saveAnalysis` must keep the item's current `friendlyName` and category name in its FTS row instead of blanking them.
- Saving an analysis resets `attempts` to 0.

- [ ] **Step 1: Write failing tests:**
  - `applyChunk_assignsAndRefreshesFts`: after applying, `search("beach")` finds an item by its category name "Beach".
  - `applyChunk_keepsUserName`
  - `applyChunk_isAllOrNothing`: an assignment naming a category that doesn't exist throws, and nothing changes.
  - `uncategorized_excludesSortedAndFailed`
  - `saveAnalysis_keepsFriendlyNameAndCategoryInFts`
  - `saveAnalysis_resetsAttempts`
  - `migrate2To3_keepsData`: a v2 DB with items, analyses, tags and FTS rows opens at v3 with all of them and an empty `sort_run`.
- [ ] **Step 2: Run them** (single-class `am instrument`) and check they fail.
- [ ] **Step 3: Implement.** Bump the version to 3 with `AutoMigration(from = 2, to = 3)` and export the schema.
- [ ] **Step 4: Run them** and check they pass. Then run the full JVM suite with `--rerun-tasks`.
- [ ] **Step 5: Device check.** Back up every project DB to the plan workspace first (`adb pull`). Install the build, then confirm the user's projects (Snapdragon Summit, Hawaii Photos, Maui) open at v3 with their analyses.
- [ ] **Step 6: Commit** `feat: project database v3 with sort runs and category writes`.

---

### Task 3: Settings — sorting model, encrypted API key, frame cap

**Files:**
- Modify: `settings/SettingsStore.kt`
- Create: `settings/ApiKeyStore.kt`
- Test: `src/test/.../settings/SettingsStoreTest.kt` (extend), `src/test/.../settings/ApiKeyStoreTest.kt`, `src/androidTest/.../settings/ApiKeyStoreDeviceTest.kt`

**Interfaces:**
- `enum class SortingModelChoice { Claude, Lfm3_1_7B, Qwen3_4B }`.
- `AppSettings` gains:
  - `sortingModel: SortingModelChoice = Lfm3_1_7B`. Default is my call: it works offline with no key.
  - `maxFramesPerClip: Int = 16`.
- `SettingsStore.setSortingModel(choice)` and `SettingsStore.setMaxFramesPerClip(n)`. `n` is clamped to the allowed values `FRAME_CAP_OPTIONS = listOf(16, 24, 32, 48, 64)`, snapping to the nearest.
- `ApiKeyStore(dataStore: DataStore<Preferences>, cipher: KeyCipher)`:
  - `suspend fun save(key: String)` trims the key and rejects a blank one.
  - `suspend fun load(): String?`
  - `suspend fun clear()`
  - `val info: Flow<ApiKeyInfo?>` with `data class ApiKeyInfo(val last4: String)`.
- `interface KeyCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(blob: ByteArray): ByteArray }`. `AndroidKeyCipher` implements it with AES/GCM/NoPadding, Keystore alias `claude_api_key`, and blob layout `iv(12) + ciphertext`. A key that can't be decrypted (Keystore reset) reads as `null` and is cleared.

- [ ] **Step 1: Write failing tests:**
  - `defaults_areLfmAnd16`
  - `frameCap_snapsToOptions`: 20 → 16, 40 → 48, 100 → 64.
  - `apiKey_roundTripsThroughCipher`, with a fake XOR cipher.
  - `apiKey_blankRejected`
  - `apiKey_undecryptable_readsNullAndClears`
  - `apiKey_info_showsLast4Only`
  - Device test `androidKeyCipher_roundTrips`.
- [ ] **Step 2: Run them** and check they fail.
- [ ] **Step 3: Implement.** The plaintext key never enters `Log`, `toString()` or an exception message.
- [ ] **Step 4: Run them** and check they pass (JVM suite plus the single device test).
- [ ] **Step 5: Commit** `feat: sorting model, encrypted Claude key and frame cap settings`.

---

### Task 4: Frame cap and an auto-sized vision context

**Files:**
- Modify: `analysis/VideoTiming.kt`, `analysis/MediaPreparer.kt`, `model/VisionModel.kt`, `model/GenieXVisionModel.kt`, `pipeline/PipelineController.kt` (read the cap at batch start), `AppGraph.kt`
- Create: `analysis/VisionBudget.kt`
- Test: `VideoTimingTest`, `VisionBudgetTest`

**Interfaces:**
- `VideoTiming.sampleTimesMs(durationMs, maxFrames)` is unchanged in behaviour. The `MAX_FRAMES` constant goes; callers pass the cap.
- `object VisionBudget`:
  - `fun contextFor(maxFrames: Int, tokensPerFrame: Int): Int`. The formula is `roundUpTo4096(maxFrames * (tokensPerFrame + 12) + 2048)`, never below 16384. The 12 covers each frame's timestamp text and marker; 2048 covers the system prompt, the instruction and a 520-token reply with margin.
  - `fun hint(maxFrames: Int): String` describes a 1-minute clip: `"1-minute clip: 32 frames, one every 1.9 s"` (frames = `min(120, maxFrames)`, interval with one decimal).
- `VisionModel.load(maxFrames: Int)` sizes `nCtx` with `VisionBudget.contextFor(maxFrames, tokenCount)`. `tokenCount` comes from the mmproj (`GgufVisionReader`, falling back to 256). If loaded with a different cap, the model reloads; log `nCtx` at load.
- The controller reads `maxFramesPerClip` once at the start of each batch and passes it to `load` and to the engine and preparer. A change mid-batch applies to the next batch.

- [ ] **Step 1: Write failing tests:**
  - `contextFor_16FramesStaysAt16384`
  - `contextFor_64Frames`: 64 × 268 + 2048 = 19200, which rounds up to 20480.
  - `hint_32Frames`: "1-minute clip: 32 frames, one every 1.9 s".
  - `hint_16Frames`: "…16 frames, one every 3.8 s".
  - `sampleTimes_capIsHonoured`: a 60 s clip at cap 32 gives 32 frames, at cap 64 gives 64; a 5 s clip gives 10 at any cap.
  - Controller test `capChange_appliesNextBatch` with a fake model recording `load(maxFrames)`.
- [ ] **Step 2: Run them** and check they fail. **Step 3: Implement.** **Step 4: Run them** and check they pass, with the full suite.
- [ ] **Step 5: Device check.** Set the cap to 32 (DataStore edit through a temporary debug hook or Task 8's UI if it already exists; otherwise a one-off `am instrument` test). Analyze a 60 s test clip. Logcat must show 32 frames and `nCtx` 16384; at cap 64 it must show `nCtx` 20480 and a reply with stopReason `eos`. Record `dumpsys meminfo` at 64.
- [ ] **Step 6: Commit** `feat: max frames per clip with an auto-sized vision context`.

---

### Task 5: The sorting contract (pure Kotlin)

**Files:**
- Create: `sorting/SortPrompt.kt`, `sorting/SortSchema.kt`, `sorting/SortParser.kt`, `sorting/SortValidator.kt`, `sorting/ChunkPlanner.kt`
- Test: one test class per file under `src/test/.../sorting/`

**Interfaces:**
- `SORT_PROMPT_VERSION = "sort-v1"`.
- `data class SortItem(id: Long, kind: ItemKind, suggestedName: String, tags: List<String>, description: String, actions: List<String>)`, built from `UncategorizedItem`.
- `SortPrompt`:
  - `system(): String`
  - `itemLine(item): String`: tab-separated `id`, `photo|video`, suggested name, tags joined with `", "`, the description cut at 240 chars on a word boundary, and for videos the actions joined with `"; "` cut at 160 chars.
  - `categoryLine(c: CategoryInfo): String`: name, item count, `locked` or `open`, description.
  - `proposeUser(items)`: the proposal pass, sending suggested name and tags only.
  - `assignUser(items, categories, feedback: List<String>)`.
- The system prompt states the spec's rules:
  - Put items into existing categories and add a new one only when nothing fits well.
  - Never rename or merge existing categories.
  - Avoid one-item categories unless an item truly fits nowhere.
  - No catch-all bin unless a handful of items fit nowhere.
  - Category names are short nouns that make good folder names: at most 3 words and 40 chars.
  - Friendly names are 2–6 words and describe the item, with no dates unless meaningful and no extension.
  - Each new category gets a one-line description.
- Reply shape, for both schema and grammar:
  ```json
  {"new_categories":[{"name":"...","description":"..."}],
   "assignments":[{"id":123,"category":"...","name":"..."}]}
  ```
  The proposal pass returns only `new_categories`.
- `SortSchema`:
  - `assignJsonSchema(): Map<String, Any>` and `proposeJsonSchema()` for Claude's `JsonOutputFormat`: `additionalProperties: false`, all fields required, `id` an integer.
  - `assignGrammar()` and `proposeGrammar()` in GBNF for GenieX, in the style of `AnalysisPrompt.grammar`.
- `SortParser.parse(text: String): SortReply?` returns null when the JSON is unreadable. `data class SortReply(newCategories: List<NewCategory>, assignments: List<RawAssignment>)`.
- `SortValidator`:
  - `fun normalize(name: String): String`: lowercase, strip diacritics and punctuation, collapse spaces, and singularize the last word (`ies→y`, `ches/shes/xes/ses→` drop `es`, a trailing `s` not after `s` dropped).
  - `fun validate(reply: SortReply, chunk: List<SortItem>, existing: List<CategoryInfo>, projectItemCount: Int): Validation`
  - `sealed interface Validation { data class Ok(val newCategories: List<CategoryEntity>, val assignments: List<Assignment>) : Validation; data class Rejected(val problems: List<String>) : Validation }`
- `validate` rules:
  - **Ids:** each chunk id appears exactly once. Ids that are missing, duplicated or not in the chunk are rejected, with problem text listing the ids.
  - **Categories:** an assignment's category must name an existing category or a new one, matched case-insensitively and then by `normalize`.
  - **Merging:** a new category whose normalized name equals an existing one's is dropped, and its items go to the existing one (user categories win). New categories that normalize equal merge into the first. Merging is not a rejection.
  - **Category names:** cleaned with the same illegal-character rule as suggested names (move `cleanName` from `AnalysisParser` to a shared `sorting/Names.kt`), at most 40 chars. An empty name after cleaning is rejected.
  - **Friendly names:** cleaned the same way, at most 60 chars. If empty, fall back to the item's suggested name.
  - **Catch-all names** (`normalize` in {misc, miscellaneou, other, general, uncategorized, various, random, unsorted}, plus the same words with "s" before normalizing): allowed only while the project's total in that bin stays ≤ `max(3, 5% of projectItemCount)`. Otherwise reject with "Too many items in a catch-all category …".
  - **One-item categories:** if more than `max(2, newCategories / 5)` new categories would end up holding exactly one item, reject once with a problem asking to fold them into broader categories. On the retry this rule is only logged, never rejected.
- `ChunkPlanner.plan(items: List<SortItem>, hasCategories: Boolean, budget: ChunkBudget): ChunkPlan`:
  - `data class ChunkBudget(val contextTokens: Int, val maxChunk: Int, val proposeSample: Int)`
  - `data class ChunkPlan(val proposal: List<SortItem>?, val chunks: List<List<SortItem>>)`
  - Estimate tokens as `chars / 3.5`, rounded up.
  - Each assignment chunk's prompt (system + categories + items) plus its reply estimate (30 tokens per item + 200) must fit in 85% of `contextTokens`, with at most `maxChunk` items.
  - Make a proposal pass when `!hasCategories && items.size > maxChunk`. It uses up to `proposeSample` items sampled evenly, kept inside the same 85% budget.
  - Budgets per backend come from Task 1's notes. Defaults until then: Claude `ChunkBudget(200_000, 200, 2000)`, local `ChunkBudget(32768, 40, 400)`.

- [ ] **Step 1: Write failing tests** with fixed fixtures:
  - `itemLine_cutsDescriptionOnWord`
  - `itemLine_videoIncludesActions`
  - `parse_readsValidReply`
  - `parse_returnsNullOnGarbage`
  - `validate_rejectsMissingDuplicateForeignIds`, whose problem text names the ids.
  - `validate_rejectsUnknownCategory`
  - `normalize_mergesNearDuplicates_intoExisting`: "Sea lion", "sea lions" and "Sea-Lions" against an existing user category "Sea Lions" end as one, the user one.
  - `validate_catchAllLimit`: 4 items in "Misc" in a project of 40 is OK; 12 is rejected.
  - `validate_tooManySingletons_rejectedOnce`
  - `validate_emptyFriendlyName_fallsBackToSuggested`
  - `validate_keepsUserNamedItemName`
  - `chunkPlan_fitsContextFor1600Items`: with the local budget, every chunk's estimate is ≤ 0.85 × 32768, and all 1,600 ids appear exactly once across chunks.
  - `chunkPlan_proposalOnlyOnFirstBatchOverChunk`
  - `assignSchema_requiresAllFields`
  - `assignGrammar_parsesSampleReply`, using the existing GBNF test approach if Plan 1 has one; otherwise assert the rule names exist.
- [ ] **Step 2: Run them** and check they fail. **Step 3: Implement.** **Step 4: Run them** and check they pass.
- [ ] **Step 5: Commit** `feat: sorting prompt, schema, parser, validator and chunk planner`.

---

### Task 6: Text model backends

**Files:**
- Create: `model/TextModel.kt`, `model/GenieXTextModel.kt`, `model/ClaudeTextModel.kt`
- Modify: `build.gradle` (Java SDK dependency and packaging excludes from Task 1), `AppGraph.kt`
- Test: `src/test/.../model/ClaudeErrorMappingTest.kt`, device smoke test `src/androidTest/.../model/GenieXTextModelTest.kt`

**Interfaces:**
- `TextModel`:
  - `val modelId: String`
  - `suspend fun load()`
  - `suspend fun complete(system: String, user: String, format: ReplyFormat, maxTokens: Int): TextReply`
  - `suspend fun cancel()`
  - `suspend fun release()`
- `data class ReplyFormat(val jsonSchema: Map<String, Any>, val grammar: String)`. Each backend uses its own half.
- `data class TextReply(val text: String, val stopReason: String, val inputTokens: Int, val outputTokens: Int, val millis: Long)`.
- `class SortingUnavailableException(val reason: SortUnavailable, message: String) : Exception(message)`
- `enum class SortUnavailable { NoApiKey, NoNetwork, BadApiKey, RateLimited, ServiceError, Refused, ModelNotDownloaded, ModelLoadFailed }`
- `fun describe(reason: SortUnavailable): String` gives the plain sentences shown to the user:

  | Reason | Text |
  | --- | --- |
  | NoApiKey | "Add a Claude API key in Settings to categorize" |
  | NoNetwork | "No connection; categorizing will try again after the next run" |
  | BadApiKey | "Claude didn't accept the API key; check it in Settings" |
  | RateLimited | "Claude is busy; categorizing will try again after the next run" |
  | ServiceError | "Claude had a problem; categorizing will try again after the next run" |
  | Refused | "Claude declined this request" |
  | ModelNotDownloaded | "Download the sorting model in Settings" |
  | ModelLoadFailed | "The sorting model couldn't start" |

- `GenieXTextModel(spec: ModelSpec, nCtx: Int)`:
  - Mirrors `GenieXVisionModel`'s locking: a `lock`/`nativeLock` pair, the cancel-on-cancellation child, and `reset()` before each call.
  - Uses `LlmWrapper` with `LlmCreateInput` (`tokenizer_path` from `getPaths()`), `applyChatTemplate(messages, null, false)`, `grammarString = format.grammar`, and the sampler from the Global Constraints.
  - A `qairt` route uses `nCtx = 0`, `nGpuLayers = 0`.
  - A missing download throws `SortingUnavailableException(ModelNotDownloaded)`.
- `ClaudeTextModel(apiKey: suspend () -> String?, clientFactory: (String) -> AnthropicClient)`:
  - `load` and `release` are no-ops. `complete` throws `NoApiKey` when the key is null.
  - Builds `MessageCreateParams` with the Global Constraints' values and the schema via `JsonOutputFormat`.
  - The fallbacks beta: take the Java builder calls from the SDK repo's examples (see the claude-api skill's live sources), never guessed.
  - Maps exceptions most specific first: `AuthenticationException` → BadApiKey, `RateLimitException` → RateLimited, `InternalServerException`/other 5xx → ServiceError, `AnthropicIoException`/`UnknownHostException` → NoNetwork. `stop_reason == "refusal"` → Refused.
  - `cancel()` cancels the in-flight call (run it in a cancellable coroutine on `Dispatchers.IO`).
  - The client is created per `complete` call from the current key and closed afterwards.

- [ ] **Step 1: Write failing JVM tests.** `ClaudeErrorMappingTest` covers the mapping table above with the SDK's exception types (construct them or mock through the client factory). `describe_coversEveryReason`.
- [ ] **Step 2: Run them** and check they fail. **Step 3: Implement.** **Step 4: Run them** and check they pass.
- [ ] **Step 5: Device smoke tests**, single-test `am instrument`:
  - `lfm_sortsTenItems`: LFM3-1.7B on 10 fixture items gives a reply that `SortParser` reads and `SortValidator` accepts, with stopReason `eos`.
  - The same for Qwen3-4B if Task 1 kept it.
  - Logcat shows each model released.
  - The Claude path is checked in Task 10 with the user's key.
- [ ] **Step 6: Commit** `feat: text model backends for sorting (GenieX and Claude)`.

---

### Task 7: Categorizer and the Categorizing phase

**Files:**
- Create: `pipeline/ModelArbiter.kt`, `pipeline/Categorizer.kt`
- Delete: `pipeline/BatchCompleteHandler.kt`
- Modify: `pipeline/PipelineController.kt`, `pipeline/AnalysisService.kt` (notification text), `AppGraph.kt`, `pipeline/PipelineFakes.kt` (tests)
- Test: `CategorizerTest`, `PipelineControllerTest` (extend), `ModelArbiterTest`

**Interfaces:**
- `enum class JobKind { Analysis, Sorting, Binning }`.
- `class ModelArbiter`:
  - `suspend fun <T> withJob(kind: JobKind, block: suspend () -> T): T` is a `Mutex`: one job at a time, FIFO.
  - `val active: StateFlow<JobKind?>`.
  - The controller wraps analysis batches and categorization in it. Plan 2b's Re-sort and Plan 3's Bin Files must too.
- `interface Categorizer { suspend fun categorize(project: ProjectRef, onProgress: (done: Int, total: Int) -> Unit): CategorizeOutcome }`
- `sealed interface CategorizeOutcome { data object NothingToDo; data class Done(val sorted: Int, val newCategories: Int); data class Partial(val sorted: Int, val left: Int, val message: String); data class Unavailable(val reason: SortUnavailable) }`
- `SortingCategorizer(dao: SortDao, modelFor: (SortingModelChoice) -> TextModel, choice: suspend () -> SortingModelChoice, budgets: (SortingModelChoice) -> ChunkBudget, log: PipelineLog)` runs:
  1. Read `uncategorized()`. If empty, return NothingToDo.
  2. Start a `sort_run` row.
  3. `load()`.
  4. The proposal pass if planned. Its new categories go into the category list the assignment chunks see, but are written only once an assignment uses them.
  5. For each chunk: complete, parse, validate. On Rejected or unreadable JSON, retry once with the problems as feedback. On a second failure, skip the chunk (its items stay Analyzed) and continue. On Ok, `applyChunk`.
  6. `release()` in `finally`.
  7. Finish the `sort_run` row.

  `SortingUnavailableException` → `Unavailable(reason)`, with the run row outcome `unavailable`. The reply cap per chunk is `30 * chunk.size + 600` tokens.
- `PipelinePhase.Categorizing` (`isRunning` is true).
- `PipelineState` gains `sortDone: Int = 0`, `sortTotal: Int = 0`, `sortNotice: String? = null`. `sortNotice` is the `describe(reason)` text or Partial's message, and is cleared when a later categorization reaches Done or NothingToDo.
- Controller:
  - Categorization runs **once per run**, after the analysis loop ends: no Pending items left and no rerun requested. It also runs when the run analyzed nothing but uncategorized items exist, so a missing key or network retries on the next run.
  - Work is picked by status (Analyzed, no category), not "this batch". This removes the old double-sorting on reruns.
  - New public `fun categorizeNow(project: ProjectRef)` starts a run that skips analysis. Plan 2b's button uses it; the same `isCurrent` and `closed` rules apply.
  - Stop and project switch cancel categorization like analysis: the run ends Stopped, and `sortNotice` is unchanged.
  - A categorization failure never sets `error` and never marks analysis failed.
- Service notification during Categorizing: "Categorizing 40 of 120".

- [ ] **Step 1: Write failing tests**, with fakes for `TextModel` and `SortDao`:
  - `categorize_appliesValidChunks`
  - `invalidReply_retriedOnceWithProblems_thenSkipped`: the second prompt contains the problem text; skipped items stay uncategorized.
  - `claudeUnavailable_leavesItemsUncategorized_withReason`: no key leads to Unavailable(NoApiKey), the state's sortNotice is the NoApiKey sentence, and the run ends Idle, not Stopped.
  - `stopDuringCategorizing_releasesModel_keepsCommittedChunks`: stopping after chunk 1 of 3 leaves chunk 1 applied, the model released, the phase Stopped, and chunks 2–3 uncategorized.
  - `categorizeRunsOncePerRun_evenWithReruns`: media added during release triggers one rerun and one categorization.
  - `runWithNothingAnalyzed_stillCategorizesLeftovers`
  - `categorizeNow_skipsAnalysis`
  - `visionReleasedBeforeSortingLoads`: the fake models record an event order of vision.release, then text.load.
  - `arbiter_runsOneJobAtATime`
- [ ] **Step 2: Run them** and check they fail. **Step 3: Implement**, replacing `BatchCompleteHandler` in `AppGraph` with `SortingCategorizer`. **Step 4: Run them** and check they pass, with the full suite at `--rerun-tasks`.
- [ ] **Step 5: Commit** `feat: categorize analyzed items after each run`.

---

### Task 8: Settings and Analyze screen UI

**Files:**
- Modify: `ui/settings/SettingsScreen.kt`, `MainActivity.kt` (wiring), `ui/analyze/AnalyzeMapping.kt`, `ui/analyze/AnalyzeScreen.kt`/`OutputCard.kt` (notice)
- Test: `AnalyzeMappingTest` (extend), `SettingsModelTest` for the pure pieces (option labels, download row state)

Layout is a glass list like the current Settings screen, top to bottom:

1. **Sorting model:** three rows with radio marks.
   - **Claude (cloud):** subtitle "Sends descriptions and tags, never photos".
   - **LFM3-1.7B (on device):** subtitle "1.05 GB".
   - **Qwen3-4B (on device):** subtitle with its size. Hidden if Task 1 found no route.
   - Picking a local model that isn't downloaded opens a confirm dialog, "Download LFM3-1.7B (1.05 GB)?", then pulls it with `ModelDownloader`. The row shows progress, then "Downloaded". A failed pull shows the failure sentence and a Retry.
   - Picking Claude without a saved key scrolls to the key field and shows "Add your API key to use Claude".
2. **Claude API key:**
   - Without a key: a masked field with a Save button.
   - With a key: "Key saved (…abcd)" with a Remove button.
   - Helper text: "Stored encrypted on this phone. Only used for sorting."
3. **Video frames:** "Max frames per clip" as chips 16 · 24 · 32 · 48 · 64, with `VisionBudget.hint(cap)` underneath.
4. Theme and Keep screen on, unchanged.

Analyze screen:
- The status row during `Categorizing` shows "Categorizing" (Active tone), with "40 of 120" in the counts slot.
- When idle with uncategorized items and a `sortNotice`, the output card shows the notice the way it shows run errors. The status row reads "12 waiting to be categorized" (Quiet tone).

- [ ] **Step 1: Write failing mapping tests:**
  - `status_categorizing`
  - `status_idleWithUncategorized`
  - `output_showsSortNotice`
  - `frameHint_inSettings`, which reuses the VisionBudget tests.
- [ ] **Step 2: Run them** and check they fail. **Step 3: Implement.** **Step 4: Run them** and check they pass.
- [ ] **Step 5: On the phone,** take dark and light screenshots: Settings (local model downloading, Claude with no key, key saved), and Analyze while Categorizing and idle with a notice. Never type a real API key. To show "Key saved", use an obviously fake value such as `sk-ant-test-0000`, then Remove it.
- [ ] **Step 6: Commit** `feat: settings for sorting and frames; categorizing status`.

---

### Task 9: Plan 1 carry-overs

**Files:** `ui/analyze/AnalyzeMapping.kt`, `AppGraph.kt`, `pipeline/AnalysisEngine.kt`, `pipeline/FailureReasons.kt` and their tests.

From `plan-2-notes.md`:
- **"Stopped" after a cold launch:** a project with `stoppedByUser` shows "Stopped", not "Waiting to analyze". Test `status_stoppedByUserAfterColdLaunch`.
- **Serialize Stop and Resume:** `AppGraph.stopAnalysis` and `resumeAnalysis` run under one `Mutex`, in call order. Test `stopThenResume_flagWrittenInOrder`.
- **Raw exception text:** messages built from `IOException` and `VisionModelException` (work-file errors, model load) use fixed sentences: "The phone ran out of space for analysis files", "The vision model couldn't start". The raw text goes to the log only. Test `environmentalErrors_areWorded`.

The attempts reset on save is done in Task 2. The other notes (breaker persistence, Add media lifting Stop, retry for Failed items) wait for Plan 2b's retry design.

- [ ] Steps: failing tests → fail → implement → pass → commit `fix: Plan 1 carry-overs (stopped label, stop/resume order, worded errors)`.

---

### Task 10: End-to-end on the phone

**Media:** the controller asks the user for a folder of about 40 photos and 10 clips of their own that aren't in another project, or for permission to categorize an existing project. Use a project "Sort Test" unless the user names one. Files are only read; nothing moves.

- [ ] **Step 1: LFM3-1.7B.**
  1. Select it in Settings (download it if needed), create "Sort Test" with the chosen media, and let analysis and categorization run with the screen locked for part of it.
  2. Pull the DB and check:
     - every Analyzed item is now `Sorted`, with a `categoryId` and a `friendlyName`;
     - no two categories normalize equal (run `SortValidator.normalize` over the names in a small JVM script);
     - a catch-all holds ≤ max(3, 5%);
     - `sort_run` has a `done` row;
     - the FTS search for one category name returns its items.
  3. Logcat shows the vision model released before LFM3-1.7B loaded, then LFM3-1.7B released.
  4. Paste the category list with counts and three friendly names per category into the report.
- [ ] **Step 2: Second batch.** Add about 10 more items. They join existing categories (new ones only where nothing fits), and existing category names are unchanged.
- [ ] **Step 3: Lock check.** On a copy of the pulled DB, set one category's `createdBy = 'user'` and push it back with the app stopped. Rename it in the DB to something odd, for example "Zzz Test", add 3 items, and confirm the model never renames it. Then restore.
- [ ] **Step 4: Stop during Categorizing.** Make sure there are ≥ 3 chunks: set the local budget's `maxChunk` to 5 through a debug build constant, then revert. Press Stop mid-way. Committed chunks remain, the rest stay Analyzed, and logcat shows the model released. Resume, and the leftovers get categorized.
- [ ] **Step 5: Qwen3-4B,** if available: re-run Step 1's checks in a second project, or on the same project after clearing categories in a DB copy. Compare the category lists in the report.
- [x] **Step 6: Claude.**
  1. The controller asks the user to enter their API key in Settings on the phone, and to approve the spend. About 50 items cost a few cents.
  2. Run with no network first (airplane mode): items stay uncategorized and the notice reads "No connection…".
  3. Reconnect, add one item: categorization runs and succeeds.
  4. Check as in Step 1, and that logcat contains no key material (`adb logcat -d | grep -c "sk-ant"` is 0).
  - Done Nov 3 on Maui (35 items, the owner's key and approval). A network that blocked the API stood in for airplane mode: run 1 `unavailable`, "No connection; categorizing will try again after the next run", nothing assigned. With the owner's VPN on, run 2 (`claude-sonnet-5-5`, `sort-v3`) `done`, 35/35 Sorted, 7 new categories, all with a category and a friendly name; `sk-ant` count 0 in logcat. Details in `docs/superpowers/notes/2026-11-01-plan-2a-phone-recheck.md`.
- [ ] **Step 7: Frame cap.** Set 32 and analyze a 60 s clip: 32 frames, `nCtx` 16384, `eos`.
- [ ] **Step 8: Write it up.** Note leftovers on the phone; delete nothing without the user's approval. Commit any test-media script changes and the report notes in CLAUDE.md, for example the new device-driving notes: `git commit -m "test: end-to-end sorting on the phone"`.
