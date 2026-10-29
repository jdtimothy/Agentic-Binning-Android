# Plan 1: Foundation and Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the copied GenieX chat sample with a fresh Compose app that creates projects, adds media to them, and analyzes every photo and clip on the NPU with LFM3-VL-2B in a background service, shown live on the glass Analyze screen.

**Architecture:** One Gradle module at the repo root (`rootProject.name = 'app'`, sources under `src/`). Pure logic (prompts, parsing, frame timing, scanning, registration rules, the pipeline state machine) lives in plain Kotlin classes with JVM unit tests. Android-bound pieces (Room database in the project folder, GenieX wrapper, media decoding, foreground service, Compose UI) sit behind small interfaces and are checked with device tests on the S26. Plan 2 adds sorting and Project Media; Plan 3 adds Bin Files.

**Tech Stack:** Kotlin 2.2.0, AGP 8.13.0, Gradle 9.1.0, Jetpack Compose (Material 3), Haze (backdrop blur), Coil 3, Room 2.7 with the bundled SQLite driver (for FTS5), DataStore, kotlinx-serialization, coroutines, `com.qualcomm.qti:geniex-android:0.10.0`.

**Spec:** `docs/superpowers/specs/2026-10-28-agentic-binning-design.md`. Also read `Instructions.md` (GenieX on the S26) and the approved mockups at https://claude.ai/artifact/KFs7XutqQQWvyFkX3aEsyw.

## Global Constraints

- Device: Galaxy S26 Ultra (SM-S948U1, SM8850, 12 GB RAM, Android 17).
- `com.qualcomm.qti:geniex-android:0.10.0`; AGP 8.13.0; Kotlin 2.2.0; Gradle 9.1.0; compileSdk 35; targetSdk 34; minSdk 31; `jniLibs.useLegacyPackaging = true`; no `ndkVersion`.
- Package and applicationId `com.agentic.binning`.
- Build from Git Bash: `JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot" ./gradlew <task>`. Set `MSYS_NO_PATHCONV=1` for `adb` commands with `/sdcard/...` paths.
- Vision model: `LiquidAI/LFM3-VL-2B-GGUF`, precision `Q4_0`, `HubSource.HUGGINGFACE`, `runtime_id` from `getPaths()` (falls back to `"llama_cpp"`), compute unit NPU.
- Vision `ModelConfig`: `nCtx = 16384`, `nThreads = 4`, `nBatch = 2048`, `nUBatch = 512`, `nGpuLayers = 999`. Never `nBatch = 1`.
- Sampler (LFM3 model card): temperature 0.1, topK 50, topP 1.0, minP 1e-6f, repetitionPenalty 1.1. Always set `maxTokens` explicitly.
- Always generate from `applyChatTemplate(...).formattedText`, never raw text. Images are absolute file paths, never content URIs. Video frames: `OPTION_CLOSEST`, padded to a square, 2 fps, max 16, timestamps `[%dm%.2fs]` after frame 0 and the first frame past each 5 s.
- Only one model loaded at a time. Release the vision model when the analysis queue empties.
- Project folder: `/storage/emulated/0/Agentic Binning/<name>/`; database `<name>/.binning/project.db`.
- Files are never moved, renamed or deleted in Plan 1. Plan 1 only reads media files.
- Glass UI tokens and layout come from the mockups (values in Task 2).

## Review Focus

1. **A folder with thousands of files.** Registration runs off the main thread and reports progress per file; the UI stays responsive. Test: Task 7 `registrar_reportsProgressForEveryFile`.
2. **Corrupt or undecodable media** (0-byte `.mp4`, truncated `.jpg`, an image `ImageDecoder` rejects). The item is marked failed and the batch carries on. Test: Task 11 `preparerFailure_marksFailed_andContinues`.
3. **A file deleted or moved after it was added but before analysis.** The item is marked missing and the batch carries on. Test: Task 11 `missingFile_marksMissing`.
4. **The app killed or the phone rebooted mid-batch.** Items left in `analyzing` go back to `pending` when the pipeline starts again. Test: Task 11 `start_resetsInterruptedItemsToPending`.
5. **Extreme clip lengths** (an hour-long video, a 0.3 s clip, a clip reporting 0 ms). Sampling always yields between 1 and 16 frames, all within the clip. Test: Task 8 `sampleTimes_extremeDurations`.

---

## File Structure

All under `src/main/java/com/agentic/binning/` unless noted. Tests mirror it under `src/test/java/...` (JVM) and `src/androidTest/java/...` (device).

| File | Responsibility |
| --- | --- |
| `BinningApp.kt` | `Application`: GenieX init, app-wide singletons (`AppGraph`) |
| `AppGraph.kt` | Hand-wired dependencies (no DI framework) |
| `MainActivity.kt` | Compose host, navigation between Analyze and Settings, keep-screen-on flag |
| `ui/theme/GlassTokens.kt` | Dark and light token sets from the mockups |
| `ui/theme/AgenticTheme.kt` | `AgenticTheme(themeMode)` and `LocalGlassTokens` |
| `ui/glass/Glass.kt` | `GlassSurface`, `GlassIconButton`, `AmbientBackdrop` |
| `ui/analyze/*.kt` | Analyze screen: top bar, status row, carousel, output card, bottom bar, view model |
| `ui/project/ProjectSheet.kt` | Create project, add media (folder or files), switch project |
| `ui/settings/SettingsScreen.kt` | Theme and Keep screen on (Plan 2 adds sorting model and API key) |
| `ui/permission/StorageAccessGate.kt` | All files access request |
| `settings/SettingsStore.kt` | DataStore-backed settings |
| `project/ProjectRegistry.kt` | App-private list of projects and the current one |
| `project/ProjectFolders.kt` | Folder naming, creation, layout paths |
| `db/*.kt` | Room entities, DAOs, `ProjectDatabase`, FTS5 setup, `openProjectDatabase` |
| `media/DocumentPaths.kt` | SAF document id to file path |
| `media/MediaScanner.kt` | Walk a folder, filter supported media |
| `media/ContentHasher.kt` | SHA-256 of a file |
| `media/MediaMetadataReader.kt` | Kind, capture time, duration, size (Android) |
| `media/Registrar.kt` | Registration rules: duplicates, cross-project guard, progress |
| `analysis/VideoTiming.kt` | Frame sample times, timestamp rule, timestamp format |
| `analysis/PromptAssembly.kt` | Interleaved `Video:` marker string and the placeholder splice |
| `analysis/AnalysisPrompt.kt` | System prompt, instructions, grammar, `PROMPT_VERSION`, token caps |
| `analysis/AnalysisParser.kt` | Full and partial JSON parsing |
| `analysis/MediaPreparer.kt` | Decode, pad, save frames and thumbnail (Android) |
| `model/ModelCatalog.kt` | Model specs (vision now; sorting entries used by Plan 2) |
| `model/ModelDownloader.kt` | `pullFlow` wrapper with progress |
| `model/VisionModel.kt` | `VisionModel` interface and result types |
| `model/GenieXVisionModel.kt` | GenieX implementation (copied from `GgufVisionConfig.kt` plus the sample's load and generate code) |
| `pipeline/AnalysisEngine.kt` | One item: prepare, generate, retry, persist |
| `pipeline/PipelineController.kt` | Queue, pause, stop, batch end, state flow |
| `pipeline/AnalysisService.kt` | Foreground service, wake lock, notification |
| `pipeline/BatchCompleteHandler.kt` | Hook called after a batch; no-op until Plan 2 |

---

### Task 1: Fresh app skeleton

**Files:**
- Delete: everything under `src/main/java/com/agentic/binning/`, `src/main/res/layout/`, `src/main/res/drawable/` (except `ic_launcher_*`), `src/main/res/color/`, `src/main/res/xml/file_paths.xml`, `src/main/assets/model_list.json`, `src/androidTest/java/com/agentic/binning/GenieXChatTest.kt`
- Keep, but first copy to `docs/reference/sample/` for later tasks: `GgufVisionConfig.kt`, `MediaUtil.kt`, `MainActivity.kt` (reference only, not compiled)
- Modify: `build.gradle`, `settings.gradle`, `src/main/AndroidManifest.xml`, `src/main/res/values/strings.xml`, `src/main/res/values/themes.xml`, `CLAUDE.md`
- Create: `BinningApp.kt`, `AppGraph.kt`, `MainActivity.kt`, `src/test/java/com/agentic/binning/SmokeTest.kt`

**Interfaces:**
- Produces: `class BinningApp : Application` with `lateinit var graph: AppGraph`; `class AppGraph(val context: Context)` (later tasks add properties).

- [ ] **Step 1: Copy the three reference files** into `docs/reference/sample/` with a `.txt` extension so they are not compiled, then delete the sample code and resources listed above.

- [ ] **Step 2: Rewrite `build.gradle`.** Keep the existing `android {}` values (namespace, compileSdk 35, minSdk 31, targetSdk 34, Java/JVM 17, `packaging { jniLibs.useLegacyPackaging = true }`). Remove `viewBinding`. Add plugins `org.jetbrains.kotlin.plugin.compose` 2.2.0 and `com.google.devtools.ksp` 2.2.0-2.0.2; `buildFeatures { compose true }`. Dependencies (use these versions; if one fails to resolve, take the nearest newer patch and note it in the commit message):

```groovy
implementation 'com.qualcomm.qti:geniex-android:0.10.0'
implementation platform('androidx.compose:compose-bom:2025.06.01')
implementation 'androidx.compose.material3:material3'
implementation 'androidx.compose.ui:ui'
implementation 'androidx.compose.ui:ui-tooling-preview'
implementation 'androidx.activity:activity-compose:1.10.1'
implementation 'androidx.lifecycle:lifecycle-runtime-compose:2.9.1'
implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1'
implementation 'androidx.lifecycle:lifecycle-service:2.9.1'
implementation 'androidx.datastore:datastore-preferences:1.1.7'
implementation 'androidx.room:room-runtime:2.7.2'
implementation 'androidx.room:room-ktx:2.7.2'
ksp 'androidx.room:room-compiler:2.7.2'
implementation 'androidx.sqlite:sqlite-bundled:2.5.2'
implementation 'androidx.exifinterface:exifinterface:1.4.1'
implementation 'io.coil-kt.coil3:coil-compose:3.2.0'
implementation 'io.coil-kt.coil3:coil-video:3.2.0'
implementation 'dev.chrisbanes.haze:haze:1.6.4'
implementation 'org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2'
testImplementation 'junit:junit:4.13.2'
testImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2'
androidTestImplementation 'androidx.test:runner:1.6.2'
androidTestImplementation 'androidx.test.ext:junit:1.2.1'
androidTestImplementation 'org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2'
```

Add the KSP plugin to `settings.gradle` `pluginManagement` if needed.

- [ ] **Step 3: Rewrite the manifest.** Permissions: `INTERNET`, `WAKE_LOCK`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `MANAGE_EXTERNAL_STORAGE` (with `tools:ignore="ScopedStorage"`). `<application android:name=".BinningApp" android:extractNativeLibs="true" android:theme="@style/Theme.AgenticBinning">`, one launcher activity `.MainActivity`. (Task 11 adds the service declaration.) `themes.xml`: `Theme.AgenticBinning` parent `android:Theme.Material.NoActionBar`, transparent status and navigation bars. `app_name` = "Agentic Binning".

- [ ] **Step 4: Write `BinningApp.onCreate`** calling `GenieXSdk.getInstance().init(this)` and creating `graph`. Write `MainActivity` showing an empty `Box` with a black background. Add `SmokeTest` asserting `1 + 1 == 2`, so the unit-test task runs.

- [ ] **Step 5: Build, test and install**

Run: `./gradlew assembleDebug testDebugUnitTest && adb install -r -t build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.agentic.binning/.MainActivity`
Expected: `BUILD SUCCESSFUL`, 1 test passed, the app opens to a black screen, and `adb logcat -d | grep -i geniex` shows no init error.

- [ ] **Step 6: Update `CLAUDE.md`.** Replace the "Current state" section with: fresh Compose app; three plans under `docs/superpowers/plans/`; sample reference copies in `docs/reference/sample/`.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: replace GenieX chat sample with fresh Compose app skeleton"
```

---

### Task 2: Glass theme and components

**Files:**
- Create: `ui/theme/GlassTokens.kt`, `ui/theme/AgenticTheme.kt`, `ui/glass/Glass.kt`
- Test: `src/test/java/com/agentic/binning/ui/theme/GlassTokensTest.kt`

**Interfaces:**
- Produces: `data class GlassTokens(...)`; `val DarkGlass: GlassTokens`, `val LightGlass: GlassTokens`; `enum class ThemeMode { System, Dark, Light }`; `@Composable fun AgenticTheme(mode: ThemeMode, content: @Composable () -> Unit)`; `val LocalGlassTokens: ProvidableCompositionLocal<GlassTokens>`; `@Composable fun GlassSurface(modifier: Modifier, shape: Shape, strong: Boolean = false, content: @Composable BoxScope.() -> Unit)`; `@Composable fun GlassIconButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, content: @Composable () -> Unit)` (44 dp circle); `@Composable fun AmbientBackdrop(colors: List<Color>, hazeState: HazeState)`; `fun contrastRatio(fg: Color, bg: Color): Double`; `fun Color.over(bg: Color): Color` (alpha composite).

`GlassTokens` fields and values, copied from the mockups:

| Field | Dark | Light |
| --- | --- | --- |
| `bg` | `#06070B` | `#E8ECF2` |
| `glass` | `rgba(24,27,36,0.52)` | `rgba(255,255,255,0.55)` |
| `glassStrong` | `rgba(30,34,45,0.66)` | `rgba(255,255,255,0.72)` |
| `border` | `rgba(255,255,255,0.13)` | `rgba(255,255,255,0.85)` |
| `highlight` | `rgba(255,255,255,0.20)` | `rgba(255,255,255,1.0)` |
| `text` | `#F4F6FA` | `#0E1116` |
| `sub` | `rgba(226,232,244,0.72)` | `#4A5263` |
| `chip` | `rgba(255,255,255,0.08)` | `rgba(20,28,45,0.06)` |
| `accent` | `#6CB6FF` | `#0A63D1` |
| `onAccent` | `#04121F` | `#FFFFFF` |
| `track` | `rgba(255,255,255,0.14)` | `rgba(20,28,45,0.10)` |
| `warn` | `#FFB547` | `#A35F00` |
| `tagBg` | `rgba(108,182,255,0.14)` | `rgba(10,99,209,0.09)` |
| `tagText` | `#D3E8FF` | `#0A4A9C` |
| `blobAlpha` | 0.55 | 0.5 |
| `dimNext` (brightness multiplier) | 0.55 | 0.82 |
| `dimPrev` | 0.42 | 0.74 |

Glass surfaces: blur radius 26 dp via Haze `hazeEffect` over `hazeSource` on the backdrop, fill `glass` (or `glassStrong`), 1 dp `border`, a 1 dp top inner highlight in `highlight`. Ambient blobs: three circles of 340, 320 and 300 dp, blurred 72–80 dp, at `blobAlpha`, colors animated over 1,400 ms.

- [ ] **Step 1: Write the failing test**

```kotlin
class GlassTokensTest {
    @Test fun dark_bodyText_meetsAA() {
        val surface = DarkGlass.glass.over(DarkGlass.bg)
        assertTrue(contrastRatio(DarkGlass.text, surface) >= 4.5)
        assertTrue(contrastRatio(DarkGlass.sub.over(surface), surface) >= 4.5)
    }
    @Test fun light_bodyText_meetsAA() {
        val surface = LightGlass.glass.over(LightGlass.bg)
        assertTrue(contrastRatio(LightGlass.text, surface) >= 4.5)
        assertTrue(contrastRatio(LightGlass.sub, surface) >= 4.5)
    }
    @Test fun accentButtonText_meetsAA() {
        assertTrue(contrastRatio(DarkGlass.onAccent, DarkGlass.accent) >= 4.5)
        assertTrue(contrastRatio(LightGlass.onAccent, LightGlass.accent) >= 4.5)
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `./gradlew testDebugUnitTest --tests '*GlassTokensTest*'`
Expected: FAIL (unresolved references).

- [ ] **Step 3: Implement the tokens, `contrastRatio` (WCAG relative luminance), `over`, `AgenticTheme` (System follows `isSystemInDarkTheme()`) and the glass composables.**

- [ ] **Step 4: Run the tests and check that they pass**

Run: `./gradlew testDebugUnitTest --tests '*GlassTokensTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: glass theme tokens and components from the mockups"
```

---

### Task 3: Settings store and Settings screen

**Files:**
- Create: `settings/SettingsStore.kt`, `ui/settings/SettingsScreen.kt`
- Modify: `AppGraph.kt` (add `settings`), `MainActivity.kt` (theme, navigation to Settings, keep screen on)
- Test: `src/test/java/com/agentic/binning/settings/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `data class AppSettings(val themeMode: ThemeMode = ThemeMode.System, val keepScreenOn: Boolean = false)`; `class SettingsStore(dataStore: DataStore<Preferences>)` with `val settings: Flow<AppSettings>`, `suspend fun setThemeMode(mode: ThemeMode)`, `suspend fun setKeepScreenOn(on: Boolean)`. Plan 2 adds fields; keep the class open to extension.

- [ ] **Step 1: Write the failing test.** Build the store on `PreferenceDataStoreFactory.create(produceFile = { tmp.newFile("s.preferences_pb") })` with a `TemporaryFolder` rule.

```kotlin
@Test fun defaults() = runTest { assertEquals(AppSettings(), store.settings.first()) }
@Test fun roundTrip() = runTest {
    store.setThemeMode(ThemeMode.Light); store.setKeepScreenOn(true)
    assertEquals(AppSettings(ThemeMode.Light, true), store.settings.first())
}
```

- [ ] **Step 2: Run it and check that it fails.** `./gradlew testDebugUnitTest --tests '*SettingsStoreTest*'`. Expected: FAIL.

- [ ] **Step 3: Implement `SettingsStore`** with preference keys `theme_mode` (enum name) and `keep_screen_on`. **Implement `SettingsScreen`:** a glass list with a three-way theme segmented control (System, Dark, Light) and a Keep screen on switch, with the caption "Keeps the screen on while the app is open and analysis is running". **In `MainActivity`:** apply `FLAG_KEEP_SCREEN_ON` when `keepScreenOn` is set and the pipeline is running. Until Task 11 exists, use the setting alone.

- [ ] **Step 4: Run the tests and check that they pass.** Expected: PASS, 2 tests.

- [ ] **Step 5: Commit** `git commit -am "feat: settings store and screen (theme, keep screen on)"`

---

### Task 4: Project database with FTS5

**Files:**
- Create: `db/Entities.kt`, `db/ItemDao.kt`, `db/AnalysisDao.kt`, `db/ProjectDatabase.kt`
- Test: `src/androidTest/java/com/agentic/binning/db/ProjectDatabaseTest.kt`

**Interfaces:**
- Produces:
  - `enum class ItemKind { Photo, Video }`; `enum class ItemStatus { Pending, Analyzing, Analyzed, Failed, Sorted, Binned, Missing }` (stored by name).
  - `@Entity("item") data class ItemEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val contentHash: String, val originalPath: String, val originalName: String, val currentPath: String, val kind: ItemKind, val takenAt: Long?, val durationMs: Long?, val width: Int?, val height: Int?, val status: ItemStatus, val categoryId: Long? = null, val friendlyName: String? = null, val nameSetByUser: Boolean = false, val categorySetByUser: Boolean = false, val addedAt: Long, val failureReason: String? = null)`, unique indices on `contentHash` and `currentPath`.
  - `@Entity("analysis") data class AnalysisEntity(@PrimaryKey val itemId: Long, val description: String, val actionsJson: String, val suggestedName: String, val shotType: String, val rawJson: String, val modelId: String, val promptVersion: String, val stopReason: String, val ttftMs: Double, val generatedTokens: Int, val analyzedAt: Long)`.
  - `TagEntity(id, name unique)`, `ItemTagEntity(itemId, tagId)` composite key.
  - `CategoryEntity(id, name, description, createdBy: String, folderName: String?)`, `MoveRunEntity(id, kind: String, startedAt, finishedAt?, state: String)`, `MoveStepEntity(id, runId, itemId, source, dest, state: String)`, `ProjectMetaEntity(id = 1, name, createdAt, schemaVersion)`. Plans 2 and 3 use these; declare them now so there is no migration.
  - `ItemDao`: `suspend fun insert(item: ItemEntity): Long`; `suspend fun findByHashes(hashes: List<String>): List<ItemEntity>`; `suspend fun findByPaths(paths: List<String>): List<ItemEntity>`; `fun observeAll(): Flow<List<ItemEntity>>` (by `addedAt`, `id`); `suspend fun nextPending(): ItemEntity?` (lowest `id` with status Pending); `suspend fun setStatus(id: Long, status: ItemStatus, failureReason: String? = null)`; `suspend fun resetStatus(from: ItemStatus, to: ItemStatus): Int`; `suspend fun countByStatus(status: ItemStatus): Int`.
  - `AnalysisDao`: `@Transaction suspend fun saveAnalysis(analysis: AnalysisEntity, tags: List<String>)` (upserts the analysis, inserts tags with `IGNORE`, replaces item tags, sets item status Analyzed and refreshes its FTS row); `suspend fun get(itemId: Long): AnalysisEntity?`; `suspend fun tagsFor(itemId: Long): List<String>`; `suspend fun search(ftsQuery: String): List<Long>` (item ids, `SELECT rowid FROM item_fts WHERE item_fts MATCH ?`).
  - `fun openProjectDatabase(context: Context, dbFile: File): ProjectDatabase`: Room `databaseBuilder` with the absolute path, `setDriver(BundledSQLiteDriver())`, `setJournalMode(JournalMode.TRUNCATE)` (the folder is on FUSE), and an `onCreate` callback that runs `CREATE VIRTUAL TABLE item_fts USING fts5(description, actions, tags, friendly_name, category, tokenize='porter unicode61')`. The FTS rowid is the item id.

- [ ] **Step 1: Write the failing device test.** Use a database in `context.cacheDir`.

```kotlin
@Test fun fts5_isAvailable_andSearchFindsAnalyzedItem() = runTest {
    val id = db.items().insert(item(hash = "h1", path = "/x/a.mp4"))
    db.analyses().saveAnalysis(analysis(id, description = "Sea lions resting on flat rocks"), listOf("sea lions", "rocks"))
    assertEquals(listOf(id), db.analyses().search("lion*"))
    assertEquals(listOf(id), db.analyses().search("rocks"))
    assertEquals(ItemStatus.Analyzed, db.items().findByHashes(listOf("h1")).single().status)
}
@Test fun duplicateHash_isRejected() = runTest {
    db.items().insert(item(hash = "h1", path = "/x/a.mp4"))
    assertThrows(SQLiteException::class.java) { runBlocking { db.items().insert(item(hash = "h1", path = "/x/b.mp4")) } }
}
@Test fun resetStatus_movesAnalyzingBackToPending() = runTest {
    val id = db.items().insert(item(hash = "h2", path = "/x/c.jpg", status = ItemStatus.Analyzing))
    assertEquals(1, db.items().resetStatus(ItemStatus.Analyzing, ItemStatus.Pending))
    assertEquals(ItemStatus.Pending, db.items().nextPending()!!.status)
}
```

Use the exception type the bundled driver actually throws (`androidx.sqlite.SQLiteException`).

- [ ] **Step 2: Run it on the phone and check that it fails**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.agentic.binning.db.ProjectDatabaseTest`
Expected: FAIL (compile errors).

- [ ] **Step 3: Implement the entities, DAOs and `openProjectDatabase`.** Tag names are stored trimmed and lowercased. The FTS `tags` column holds tags joined with spaces; `category` and `friendly_name` are empty strings until Plan 2.

- [ ] **Step 4: Run it and check that it passes.** Expected: 3 tests pass. If `fts5` is missing from the bundled driver, stop and report; don't fall back to FTS4 silently.

- [ ] **Step 5: Commit** `git commit -am "feat: project database with FTS5 search"`

---

### Task 5: Project folders and registry

**Files:**
- Create: `project/ProjectFolders.kt`, `project/ProjectRegistry.kt`
- Modify: `AppGraph.kt` (add `registry`, `projectsRoot = File(Environment.getExternalStorageDirectory(), "Agentic Binning")`, `currentProject: StateFlow<OpenProject?>`)
- Test: `src/test/java/com/agentic/binning/project/ProjectFoldersTest.kt`, `ProjectRegistryTest.kt`

**Interfaces:**
- Produces:
  - `object ProjectFolders { fun folderNameFor(projectName: String): String; fun binningDir(projectDir: File): File /* .binning */; fun databaseFile(projectDir: File): File /* .binning/project.db */; fun thumbsDir(projectDir: File): File /* .binning/thumbs */ }`.
  - `@Serializable data class ProjectRef(val name: String, val path: String)`.
  - `class ProjectRegistry(registryFile: File, projectsRoot: File)` with `fun list(): List<ProjectRef>`; `fun create(name: String): Result<ProjectRef>` (creates the folder, `.binning/`, `.binning/thumbs/` and an empty `.binning/.nomedia`; fails with `ProjectExistsException` if the folder already exists, `IllegalArgumentException` on a blank name); `fun current(): ProjectRef?`; `fun setCurrent(ref: ProjectRef)`; `fun projectContaining(path: String): ProjectRef?` (the project whose folder is an ancestor of `path`).
  - `class OpenProject(val ref: ProjectRef, val db: ProjectDatabase)` (built in `AppGraph.openProject(ref)` with `openProjectDatabase`, which also writes `ProjectMetaEntity` on first open).
  - The registry file is `filesDir/projects.json`: `{"projects":[...],"current":"<path>"}`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun folderName_replacesIllegalCharacters() {
    assertEquals("La Jolla Trip", ProjectFolders.folderNameFor("  La Jolla Trip "))
    assertEquals("Trip_ 2026_07", ProjectFolders.folderNameFor("Trip/ 2026:07"))
    assertEquals("Trip", ProjectFolders.folderNameFor("Trip..."))
}
@Test fun create_makesLayout_andRegisters() {
    val ref = registry.create("La Jolla Trip").getOrThrow()
    assertTrue(File(ref.path, ".binning/thumbs").isDirectory)
    assertTrue(File(ref.path, ".binning/.nomedia").isFile)
    assertEquals(listOf(ref), registry.list())
}
@Test fun create_existingFolder_fails() {
    registry.create("A").getOrThrow()
    assertTrue(registry.create("A").exceptionOrNull() is ProjectExistsException)
}
@Test fun current_persistsAcrossInstances() {
    val ref = registry.create("A").getOrThrow(); registry.setCurrent(ref)
    assertEquals(ref, ProjectRegistry(registryFile, root).current())
}
@Test fun projectContaining_matchesOnlyDescendants() {
    val a = registry.create("A").getOrThrow(); registry.create("AB").getOrThrow()
    assertEquals(a, registry.projectContaining("${a.path}/Sea lions/x.mp4"))
    assertNull(registry.projectContaining("${root.path}/ABC/x.mp4"))
}
```

`folderNameFor` replaces each of `/ \ : * ? " < > |` with `_`, trims spaces and trailing dots, and caps the result at 80 characters.

- [ ] **Step 2: Run them and check that they fail.** Expected: FAIL.
- [ ] **Step 3: Implement.** Compare paths with canonical paths plus a trailing separator, so `/A` doesn't match `/AB`.
- [ ] **Step 4: Run them and check that they pass.** Expected: 5 tests pass.
- [ ] **Step 5: Commit** `git commit -am "feat: project folders and registry"`

---

### Task 6: All files access and the project sheet

**Files:**
- Create: `ui/permission/StorageAccessGate.kt`, `ui/project/ProjectSheet.kt`
- Modify: `MainActivity.kt`

**Interfaces:**
- Consumes: `ProjectRegistry`, `AppGraph.openProject`.
- Produces: `@Composable fun StorageAccessGate(content: @Composable () -> Unit)`; `data class ProjectSheetState(val projects: List<ProjectRef>, val current: ProjectRef?, val createError: String?, val registration: RegistrationProgress?, val registrationSummary: String?)` (`RegistrationProgress` comes from Task 7; use `null` until then); `@Composable fun ProjectSheet(state: ProjectSheetState, onCreate: (String) -> Unit, onSwitch: (ProjectRef) -> Unit, onAddFolder: () -> Unit, onAddFiles: () -> Unit, onDismiss: () -> Unit)`. In Task 7, the folder and file pickers deliver `Uri`s to `Registrar`.

- [ ] **Step 1: Implement `StorageAccessGate`.** If `Environment.isExternalStorageManager()` is false, show a glass card ("Agentic Binning needs access to all files so it can read your media and, later, move it into project folders.") with a button that opens `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` for this package. Re-check on resume. Also request `POST_NOTIFICATIONS` once.

- [ ] **Step 2: Implement `ProjectSheet`** as a glass bottom sheet with:
  - **Create project:** a text field and a Create button; errors are shown inline ("A project folder with that name already exists").
  - **Add media:** two buttons, "Add a folder" (`ActivityResultContracts.OpenDocumentTree`) and "Pick photos and clips" (`ActivityResultContracts.OpenMultipleDocuments` with `arrayOf("image/*", "video/*")`). Both are disabled when no project is open.
  - **Your projects:** a list; tapping one switches to it.

  When the app opens with no project, the top-bar project button reads **Create project**.

- [ ] **Step 3: Check on the phone.** Install and launch. Grant all files access. Create "Plan1 Test" and confirm `adb shell ls "/storage/emulated/0/Agentic Binning/Plan1 Test/.binning"` lists `project.db`, `thumbs` and `.nomedia`. Kill and relaunch the app, and confirm "Plan1 Test" is still the current project.

- [ ] **Step 4: Commit** `git commit -am "feat: all files access gate and project sheet"`

---

### Task 7: Media scanning and registration

**Files:**
- Create: `media/DocumentPaths.kt`, `media/MediaScanner.kt`, `media/ContentHasher.kt`, `media/MediaMetadataReader.kt`, `media/Registrar.kt`
- Modify: `ui/project/ProjectSheet.kt` wiring, `AppGraph.kt`
- Test: `src/test/java/com/agentic/binning/media/DocumentPathsTest.kt`, `MediaScannerTest.kt`, `ContentHasherTest.kt`, `RegistrarTest.kt`

**Interfaces:**
- Produces:
  - `object DocumentPaths { fun treeDocIdToPath(docId: String): String?; fun mediaDocId(docId: String): Pair<ItemKind, Long>? }`. `"primary:DCIM/Camera"` → `/storage/emulated/0/DCIM/Camera`; `"1A2B-3C4D:Movies"` → `/storage/1A2B-3C4D/Movies`; `"video:1234"` → `Video to 1234`. For media ids, the Android side resolves the path with a `MediaStore` query of `_data`, which works with all files access.
  - `object MediaScanner { val PHOTO_EXT = setOf("jpg","jpeg","png","heic","heif","webp"); val VIDEO_EXT = setOf("mp4","mov","m4v","3gp","mkv","webm"); fun kindOf(file: File): ItemKind?; fun scan(dir: File, exclude: (File) -> Boolean): Sequence<File> }`. Recursive. Skips hidden entries (names starting with `.`) and any directory for which `exclude` returns true.
  - `object ContentHasher { fun sha256(file: File): String }`: lowercase hex, 1 MiB buffer.
  - `data class Candidate(val file: File, val kind: ItemKind)`; `sealed interface RegistrationOutcome { data class Added(val path: String); data class DuplicateInProject(val path: String); data class HeldByOtherProject(val path: String, val projectName: String); data class Unreadable(val path: String, val reason: String) }`; `data class RegistrationProgress(val done: Int, val total: Int, val last: RegistrationOutcome)`.
  - `interface ProjectItemLookup { val projectName: String; suspend fun containsHashOrPath(hash: String, path: String): Boolean }`.
  - `class Registrar(private val thisProject: ProjectRef, private val itemDao: ItemDao, private val otherProjects: List<ProjectItemLookup>, private val registry: ProjectRegistry, private val hash: (File) -> String = ContentHasher::sha256, private val metadata: (Candidate) -> MediaMetadata, private val clock: () -> Long)` with `fun register(candidates: List<Candidate>): Flow<RegistrationProgress>`. Order of checks for each file:
    1. a file inside another project's folder → `HeldByOtherProject`;
    2. hash or path already in this project → `DuplicateInProject`;
    3. hash or path in another project → `HeldByOtherProject`;
    4. otherwise insert as `Pending` → `Added`.

    An exception while reading → `Unreadable`, and the flow continues.
  - `data class MediaMetadata(val takenAt: Long?, val durationMs: Long?, val width: Int?, val height: Int?)`; `MediaMetadataReader.read(context, candidate)` uses EXIF `DateTimeOriginal` for photos, `METADATA_KEY_DATE` plus duration and size for videos, and falls back to `lastModified`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// DocumentPathsTest
assertEquals("/storage/emulated/0/DCIM/Camera", DocumentPaths.treeDocIdToPath("primary:DCIM/Camera"))
assertEquals("/storage/1A2B-3C4D/Movies", DocumentPaths.treeDocIdToPath("1A2B-3C4D:Movies"))
assertEquals("/storage/emulated/0", DocumentPaths.treeDocIdToPath("primary:"))
assertEquals(ItemKind.Video to 1234L, DocumentPaths.mediaDocId("video:1234"))
assertNull(DocumentPaths.mediaDocId("audio:5"))

// MediaScannerTest: tree a/{x.JPG, y.mp4, notes.txt, .hidden/z.jpg, .binning/t.jpg, sub/w.mov}, other/{q.jpg}
assertEquals(setOf("x.JPG","y.mp4","w.mov"), MediaScanner.scan(root, exclude = { it.name == "other" }).map { it.name }.toSet())

// ContentHasherTest
assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ContentHasher.sha256(fileWith("abc")))

// RegistrarTest (fake ItemDao and lookups, in-memory)
@Test fun duplicateHashInProject_isSkipped()
@Test fun fileInsideOtherProjectFolder_isRefused_namingThatProject()
@Test fun hashHeldByOtherProject_isRefused_namingThatProject()
@Test fun unreadableFile_isReported_andOthersStillAdded()
@Test fun registrar_reportsProgressForEveryFile() // 2,000 candidates → 2,000 progress events, last.done == 2000
@Test fun added_itemsArePendingWithMetadata()
```

Each `RegistrarTest` asserts the exact `RegistrationOutcome` type and, where relevant, `projectName`.

- [ ] **Step 2: Run them and check that they fail.** Expected: FAIL.

- [ ] **Step 3: Implement.** To make `ItemDao` fakeable, `Registrar` depends on a narrow `interface ItemStore { suspend fun findByHashes(...); suspend fun findByPaths(...); suspend fun insert(...) }`, which `ItemDao` implements. `AppGraph` builds a `ProjectItemLookup` for each other registered project by opening that project's database read-only on demand.

- [ ] **Step 4: Run them and check that they pass.** Expected: all pass.

- [ ] **Step 5: Wire the sheet.** The folder picker goes through `treeDocIdToPath` and then `scan`. The file picker sends `externalstorage` documents through `treeDocIdToPath` and `media` documents through a MediaStore lookup. Registration runs on `Dispatchers.IO`. The sheet shows "Adding 340 of 1,200…", then a summary such as "1,180 added · 15 already in this project · 5 in another project (La Jolla Trip)". When registration finishes, start the pipeline (`PipelineController.start(project)`, Task 11; until then, leave a TODO-free no-op `onRegistered` callback).

- [ ] **Step 6: Commit** `git commit -am "feat: media scanning and registration with duplicate and cross-project guards"`

---

### Task 8: Video timing and prompt assembly

**Files:**
- Create: `analysis/VideoTiming.kt`, `analysis/PromptAssembly.kt`
- Test: `src/test/java/com/agentic/binning/analysis/VideoTimingTest.kt`, `PromptAssemblyTest.kt`

**Interfaces:**
- Produces:
  - `object VideoTiming { const val FPS = 2f; const val MAX_FRAMES = 16; fun sampleTimesMs(durationMs: Long, maxFrames: Int = MAX_FRAMES): List<Long>; fun timestampedIndices(timesMs: List<Long>): Set<Int>; fun format(timeMs: Long): String }`. Port these from `docs/reference/sample/MediaUtil.kt.txt`, behavior unchanged, except that `durationMs <= 0` returns `[0]`.
  - `object PromptAssembly { const val MEDIA_MARKER = "<__media__>"; const val PLACEHOLDER = "@@GENIEX_MEDIA_TURN@@0@@"; fun videoMediaText(timesMs: List<Long>): String; fun photoMediaText(): String /* MEDIA_MARKER */; fun splice(formattedText: String, mediaText: String, instruction: String): String }`. `splice` replaces `PLACEHOLDER` with `mediaText + "\n" + instruction`, and throws `IllegalStateException` if the placeholder is missing or appears more than once.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun sampleTimes_shortClipAt2fps() = assertEquals((0L until 16L).map { it * 500 }, VideoTiming.sampleTimesMs(8_000))
@Test fun sampleTimes_longClipSpreadEvenly() = assertEquals((0L until 16L).map { 14_000 * it / 16 }, VideoTiming.sampleTimesMs(14_000))
@Test fun sampleTimes_extremeDurations() {
    val hour = VideoTiming.sampleTimesMs(3_600_000)
    assertEquals(16, hour.size); assertTrue(hour.last() < 3_600_000)
    assertEquals(listOf(0L), VideoTiming.sampleTimesMs(300))
    assertEquals(listOf(0L), VideoTiming.sampleTimesMs(0))
    assertEquals(listOf(0L), VideoTiming.sampleTimesMs(-5))
}
@Test fun timestamps_first_andEvery5s() = assertEquals(setOf(0, 10), VideoTiming.timestampedIndices(VideoTiming.sampleTimesMs(8_000)))
@Test fun format_matchesLlamaCpp() { assertEquals("[0m0.00s]", VideoTiming.format(0)); assertEquals("[1m5.50s]", VideoTiming.format(65_500)) }
@Test fun videoMediaText_interleaves() = assertEquals(
    "Video:<__media__>[0m0.00s]<__media__><__media__>", PromptAssembly.videoMediaText(listOf(0, 500, 1000)))
@Test fun splice_replacesPlaceholderOnce() = assertEquals(
    "<|im_start|>user\n<__media__>\nDescribe.<|im_end|>",
    PromptAssembly.splice("<|im_start|>user\n${PromptAssembly.PLACEHOLDER}<|im_end|>", "<__media__>", "Describe."))
@Test fun splice_missingPlaceholder_throws()
```

- [ ] **Step 2: Run them and check that they fail.** Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and check that they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "feat: video frame timing and media prompt splicing"`

---

### Task 9: Analysis prompt, grammar and parser

**Files:**
- Create: `analysis/AnalysisPrompt.kt`, `analysis/AnalysisParser.kt`
- Test: `src/test/java/com/agentic/binning/analysis/AnalysisParserTest.kt`, `AnalysisPromptTest.kt`

**Interfaces:**
- Produces:
  - `object AnalysisPrompt { const val PROMPT_VERSION = "analysis-v2"; const val MAX_TOKENS_PHOTO = 400; const val MAX_TOKENS_VIDEO = 520; val SYSTEM: String; fun instruction(kind: ItemKind): String; fun grammar(kind: ItemKind): String }` (updated during Task 10: per-kind grammars, video 2–5 actions with m:ss times, photo `[]`, 3–10 tags).
  - `@Serializable data class ActionEntry(val t: String, val text: String)`; `data class AnalysisResult(val description: String, val actions: List<ActionEntry>, val tags: List<String>, val suggestedName: String, val shotType: String)`.
  - `object AnalysisParser { fun parse(text: String, kind: ItemKind): AnalysisResult?; fun partial(text: String): PartialAnalysis }`; `data class PartialAnalysis(val description: String, val descriptionDone: Boolean, val actions: List<ActionEntry>, val tags: List<String>)`.

Prompt copy (fixed; changing it requires bumping `PROMPT_VERSION`):

```text
SYSTEM:
You catalogue a personal photo and video library. Look carefully and report only what is visible.
Reply with one JSON object and nothing else, with these keys:
"description": 1-3 plain sentences: the subject, the setting, and anything notable.
"actions": for a video, 2-5 entries {"t": "m:ss", "text": "what happens"} in time order, using the timestamps shown; for a photo, [].
"tags": 5-10 short lowercase keywords for searching (subjects, setting, activity, time of day).
"suggested_name": 2-5 words naming the shot, like "Sea lions on the rocks". No file extension, no date.
"shot_type": "wide", "medium" or "close".

instruction(Photo): Describe this photo.
instruction(Video): Describe this video clip and how it changes over time.
```

Grammar (GBNF, passed as `SamplerConfig.grammarString`):

```text
root    ::= "{" ws "\"description\":" ws str "," ws "\"actions\":" ws actions "," ws "\"tags\":" ws strs "," ws "\"suggested_name\":" ws str "," ws "\"shot_type\":" ws shot ws "}"
actions ::= "[" ws ( action ( "," ws action )* )? ws "]"
action  ::= "{" ws "\"t\":" ws str "," ws "\"text\":" ws str ws "}"
strs    ::= "[" ws ( str ( "," ws str )* )? ws "]"
shot    ::= "\"wide\"" | "\"medium\"" | "\"close\""
str     ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] )* "\""
ws      ::= [ \t\n]*
```

Parser rules:
- Tags are trimmed, lowercased, de-duplicated in order and capped at 10.
- For a photo, `actions` is forced to empty.
- `suggestedName` is trimmed, stripped of `/ \ : * ? " < > |` and trailing dots, and capped at 60 characters.
- Return `null` if `description` is blank, if `suggestedName` is blank after cleaning, or if the text isn't a JSON object.
- `shotType` outside the three values becomes `"medium"`.
- Ignore any text before the first `{`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun parse_video() {
    val r = AnalysisParser.parse("""{"description":"Sea lions on rocks.","actions":[{"t":"0:00","text":"Pan right"}],"tags":["Sea Lions","rocks","sea lions"],"suggested_name":"Sea lions on the rocks","shot_type":"wide"}""", ItemKind.Video)!!
    assertEquals(listOf("sea lions", "rocks"), r.tags); assertEquals("0:00", r.actions.single().t); assertEquals("wide", r.shotType)
}
@Test fun parse_photo_dropsActions()
@Test fun parse_cleansSuggestedName() // "Pier: night/moon..." -> "Pier night moon"
@Test fun parse_rejectsBlankDescription()
@Test fun parse_rejectsTruncatedJson() // text cut mid-string -> null
@Test fun parse_unknownShotType_becomesMedium()
@Test fun partial_streamsDescription() {
    val p = AnalysisParser.partial("""{"description":"Golden-hour view acr""")
    assertEquals("Golden-hour view acr", p.description); assertFalse(p.descriptionDone)
}
@Test fun partial_afterDescription_collectsCompletedActionsAndTags()
@Test fun grammar_and_prompt_mentionSameKeys() // every key in SYSTEM appears in GRAMMAR
```

`parse_cleansSuggestedName` expects `"Pier night moon"`: illegal characters become spaces, runs of spaces collapse, and trailing dots are trimmed.

- [ ] **Step 2: Run them and check that they fail.** Expected: FAIL.
- [ ] **Step 3: Implement.** Use kotlinx-serialization `Json { ignoreUnknownKeys = true; isLenient = false }` for `parse`. `partial` is a small hand-written scanner: it finds `"description":"`, reads characters (handling escapes) until an unescaped quote, and then parses the complete `{"t":...}` objects and tag strings that follow.
- [ ] **Step 4: Run them and check that they pass.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "feat: analysis prompt, JSON grammar and parser"`

---

### Task 10: GenieX vision model on the NPU

**Files:**
- Create: `model/ModelCatalog.kt`, `model/ModelDownloader.kt`, `model/VisionModel.kt`, `model/GenieXVisionModel.kt`, `model/GgufVisionConfig.kt` (ported from the reference copy), `analysis/MediaPreparer.kt`
- Test: `src/androidTest/java/com/agentic/binning/model/GenieXVisionModelTest.kt`

**Interfaces:**
- Consumes: `VideoTiming`, `PromptAssembly`, `AnalysisPrompt`, `AnalysisParser`.
- Produces:
  - `data class ModelSpec(val id: String, val displayName: String, val repo: String, val precision: String, val hub: HubSource, val chipset: String? = null)`; `object ModelCatalog { val LFM3_VL_2B: ModelSpec /* "lfm3-vl-2b", "LFM3-VL-2B", "LiquidAI/LFM3-VL-2B-GGUF", "Q4_0", HUGGINGFACE */; val LFM3_1_7B: ModelSpec /* "lfm3-1.7b", "LFM3-1.7B", "LiquidAI/LFM3-1.7B-GGUF", "Q4_0", HUGGINGFACE */ }`. Plan 2 adds Qwen3-8B after verifying its identifier.
  - `sealed interface DownloadState { object Absent; data class Downloading(val fraction: Float); object Ready; data class Failed(val message: String) }`; `class ModelDownloader { fun state(spec: ModelSpec): Flow<DownloadState>; suspend fun isReady(spec: ModelSpec): Boolean; fun pull(spec: ModelSpec): Flow<DownloadState> }`. It holds a partial wake lock while pulling, and computes progress as the sum of `downloaded_bytes` over the sum of `total_bytes`.
  - `data class PreparedMedia(val kind: ItemKind, val imagePaths: List<String>, val frameTimesMs: List<Long>, val thumbnailPath: String)`; `class MediaPreparer(context: Context, workDir: File)` with `fun prepare(item: ItemEntity, thumbsDir: File, targetEdge: Int): PreparedMedia` (throws `FileNotFoundException` if the file is gone, `MediaDecodeException` if it can't be decoded) and `fun cleanup(itemId: Long)`. Photos: `ImageDecoder` with `ALLOCATOR_SOFTWARE`, longest edge 1,536 px, padded to a square, scaled to `targetEdge`, saved as JPEG 90 to `workDir/<itemId>/image.jpg`. Videos: frames at `VideoTiming.sampleTimesMs` via `getScaledFrameAtTime(t * 1000, OPTION_CLOSEST, 1536, 1536)`, padded to a square, saved to `workDir/<itemId>/f_<ms>.jpg`. In both cases, a 512 px thumbnail (first frame, not padded) goes to `thumbsDir/<itemId>.jpg`.
  - `data class VisionProfile(val stopReason: String, val ttftMs: Double, val promptTokens: Int, val generatedTokens: Int, val decodingSpeed: Double)`; `data class VisionReply(val text: String, val profile: VisionProfile)`.
  - `interface VisionModel { val modelId: String; val imageEdge: Int; suspend fun load(); suspend fun generate(media: PreparedMedia, onToken: (String) -> Unit): VisionReply; suspend fun cancel(); suspend fun release() }`.
  - `class GenieXVisionModel(spec: ModelSpec, useGrammar: Boolean = true) : VisionModel`.
    - `load()`: `getPaths(spec.repo)`, reads `GgufVisionConfig` from `mmproj_path` (sets `imageEdge`, falling back to 512), then builds `VlmWrapper` with the Global Constraints config and `compute_unit = ComputeUnitValue.NPU.value`.
    - `generate`:
      1. `reset()`;
      2. template `[system(SYSTEM), user(text PLACEHOLDER)]`;
      3. `splice` in the media text and the instruction;
      4. `injectMediaPathsToConfig` with only the image parts, in marker order;
      5. `maxTokens` from `AnalysisPrompt`, the sampler from the Global Constraints, and `grammarString = AnalysisPrompt.grammar(media.kind)` when `useGrammar`;
      6. stream tokens to `onToken`, and return the text and profile from `LlmStreamResult.Completed`.
    - `cancel()`: `stopStream()`. `release()`: `stopStream()` then `destroy()`.
    - `modelId` = `"${spec.repo}:${spec.precision}"`.

- [ ] **Step 1: Write the device test.** It pulls the model if absent (allow up to 10 minutes). It then draws a 1,024×768 bitmap with the text "HELLO S26", a red square and a blue square, saves it, and wraps it as a photo `PreparedMedia`.

```kotlin
@Test fun photo_onNpu_returnsParseableJson() = runBlocking {
    model.load()
    val reply = model.generate(photo) {}
    val result = AnalysisParser.parse(reply.text, ItemKind.Photo)
    assertNotNull(reply.text, result)
    assertEquals("eos", reply.profile.stopReason)
    assertTrue(reply.profile.generatedTokens < AnalysisPrompt.MAX_TOKENS_PHOTO)
    model.release()
}
@Test fun video_frames_interleaveWithTimestamps() = runBlocking { /* 8 synthetic frames at 0..3500 ms, a square that turns red from 2,000 ms; assert parse succeeds and actions are non-empty */ }
```

- [ ] **Step 2: Run on the phone and check that it fails.** Expected: FAIL (compile errors).
- [ ] **Step 3: Implement**, porting `GgufVisionReader` and the template, splice and inject sequence from `docs/reference/sample/MainActivity.kt.txt` (lines 686–735 there).
- [ ] **Step 4: Run on the phone and check that it passes**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.agentic.binning.model.GenieXVisionModelTest`
Expected: 2 tests pass, and `adb logcat -d | grep htp` shows `libggml-htp-v81.so` (NPU proof). If the grammar makes generation fail or stall, rerun with `useGrammar = false`. If that passes, keep the default `false` and note it in `CLAUDE.md`; the parser still validates every reply.

- [ ] **Step 5: Commit** `git commit -am "feat: LFM3-VL-2B vision model on the NPU with media preparation"`

---

### Task 11: Analysis engine, pipeline controller and foreground service

**Files:**
- Create: `pipeline/AnalysisEngine.kt`, `pipeline/PipelineController.kt`, `pipeline/AnalysisService.kt`, `pipeline/BatchCompleteHandler.kt`
- Modify: `AppGraph.kt`, `MainActivity.kt` (keep screen on while running), `ui/project/ProjectSheet.kt` (start after registration)
- Test: `src/test/java/com/agentic/binning/pipeline/AnalysisEngineTest.kt`, `PipelineControllerTest.kt`

**Interfaces:**
- Consumes: `VisionModel`, `MediaPreparer` (behind `interface Preparer { fun prepare(item: ItemEntity): PreparedMedia; fun cleanup(itemId: Long) }`), `ItemDao` and `AnalysisDao` (behind `interface AnalysisStore { suspend fun nextPending(): ItemEntity?; suspend fun setStatus(id: Long, status: ItemStatus, reason: String? = null); suspend fun resetStatus(from: ItemStatus, to: ItemStatus): Int; suspend fun save(itemId: Long, result: AnalysisResult, raw: String, modelId: String, profile: VisionProfile) }`).
- Produces:
  - `sealed interface ItemOutcome { data class Analyzed(val result: AnalysisResult); data class Failed(val reason: String); object Missing; object Cancelled }`.
  - `class AnalysisEngine(model: VisionModel, preparer: Preparer, store: AnalysisStore)` with `suspend fun analyze(item: ItemEntity, onPartial: (PartialAnalysis) -> Unit): ItemOutcome`. It retries once on `stopReason == "length"` or when `AnalysisParser.parse` returns null. After the second failure → `Failed("length")` or `Failed("invalid-json")`. `FileNotFoundException` → `Missing`. A decode failure → `Failed("decode")`. It always calls `cleanup`.
  - `enum class PipelinePhase { Idle, LoadingModel, Preparing, Describing, Paused, Stopped, Releasing }`; `data class PipelineState(val phase: PipelinePhase, val project: ProjectRef?, val currentItemId: Long?, val partial: PartialAnalysis?, val lastProfile: VisionProfile?, val pending: Int, val analyzed: Int, val total: Int)`.
  - `class PipelineController(scope: CoroutineScope, engineFactory: () -> AnalysisEngine, model: VisionModel, store: AnalysisStore, batchComplete: BatchCompleteHandler)` with `val state: StateFlow<PipelineState>`, `fun start(project: ProjectRef)`, `fun pause()`, `fun resume()`, `fun stop()`.
  - `fun interface BatchCompleteHandler { suspend fun onBatchComplete(project: ProjectRef) }`. `AppGraph` binds a no-op for now.

Controller rules (from the spec):
- `start` first runs `resetStatus(Analyzing, Pending)`, then loads the model if needed.
- It loops over `nextPending()`, setting each item `Analyzing` before the engine runs.
- `pause` lets the current item finish, then holds in `Paused` with the model still loaded.
- `stop` cancels the current generation (`model.cancel()`), puts the current item back to `Pending`, releases the model, and ends in `Stopped`. `resume` after `stop` behaves like `start`.
- When `nextPending()` returns null: phase `Releasing`, then `model.release()`, then `batchComplete.onBatchComplete(project)` exactly once, then `Idle`.
- Release always happens before the handler is called.

`AppGraph` adapts the DAOs to `AnalysisStore`. Its `save` builds an `AnalysisEntity` (with `promptVersion = AnalysisPrompt.PROMPT_VERSION`, `actionsJson` as the JSON-encoded `actions`, and `analyzedAt = now`) and calls `AnalysisDao.saveAnalysis(entity, result.tags)`.

`AnalysisService` is a `LifecycleService`, declared in the manifest as `<service android:name=".pipeline.AnalysisService" android:foregroundServiceType="dataSync" android:exported="false"/>`. It starts in the foreground with a notification ("Analyzing La Jolla Trip · 12 left", with Pause and Stop actions), holds a partial wake lock while the controller is not `Idle` or `Stopped`, and stops itself on `Idle` or `Stopped`. `PipelineController.start` is invoked through the service, so analysis survives the activity going away.

- [ ] **Step 1: Write the failing tests** (fakes for the model, preparer and store; `StandardTestDispatcher`)

```kotlin
// AnalysisEngineTest
@Test fun validReply_isSaved()
@Test fun lengthStop_retriesOnce_thenSucceeds()
@Test fun invalidJsonTwice_marksFailed_invalidJson()
@Test fun missingFile_marksMissing()
@Test fun preparerFailure_marksFailed_andContinues() // engine returns Failed("decode"); controller test checks the next item still runs
@Test fun cleanup_alwaysCalled()

// PipelineControllerTest
@Test fun start_resetsInterruptedItemsToPending()
@Test fun processesAllPending_thenReleases_thenCallsBatchCompleteOnce()
@Test fun release_happensBeforeBatchComplete() // record call order: ["release", "batchComplete"]
@Test fun pause_finishesCurrentItem_thenHolds_withModelLoaded()
@Test fun stop_cancelsCurrent_returnsItToPending_andReleases()
@Test fun resumeAfterStop_continuesWithRemainingItems()
@Test fun failedItem_doesNotStopTheBatch()
```

- [ ] **Step 2: Run them and check that they fail.** Expected: FAIL.
- [ ] **Step 3: Implement** the engine, the controller (a single coroutine loop; pause and stop signalled with a `MutableStateFlow` the loop checks between items, and `model.cancel()` for stop) and the service.
- [ ] **Step 4: Run them and check that they pass.** `./gradlew testDebugUnitTest --tests '*pipeline*'`. Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "feat: analysis engine, pipeline controller and foreground service"`

---

### Task 12: Analyze screen

**Files:**
- Create: `ui/analyze/AnalyzeViewModel.kt`, `ui/analyze/AnalyzeScreen.kt`, `ui/analyze/TopBar.kt`, `ui/analyze/CardCarousel.kt`, `ui/analyze/OutputCard.kt`, `ui/analyze/BottomBar.kt`, `ui/analyze/CardPose.kt`
- Test: `src/test/java/com/agentic/binning/ui/analyze/CardPoseTest.kt`

**Interfaces:**
- Consumes: `PipelineController.state`, `ItemDao.observeAll()`, `AnalysisDao.get`, `ModelDownloader.state(LFM3_VL_2B)`, `ProjectRegistry`, `SettingsStore`.
- Produces:
  - `data class CardPose(val translationXDp: Float, val rotationYDeg: Float, val scale: Float, val alpha: Float, val brightness: Float, val z: Float)`; `fun cardPose(offset: Int, count: Int, tokens: GlassTokens): CardPose`, where `offset` is the item's position relative to the current item.
  - `@Composable fun AnalyzeScreen(vm: AnalyzeViewModel, onOpenSettings: () -> Unit, onOpenProjectSheet: () -> Unit)`.

Poses (from the mockup):

| offset | translationX (dp) | rotationY | scale | alpha | brightness | z |
| --- | --- | --- | --- | --- | --- | --- |
| 0 (current) | 0 | 0 | 1.0 | 1 | 1.0 | 5 |
| +1 (next) | 200 | −38 | 0.84 | 1 | `dimNext` | 4 |
| −1 (done) | −200 | 38 | 0.84 | 1 | `dimPrev` | 4 |
| ≥ +2 | 520 | −70 | 0.7 | 0 | `dimNext` | 1 |
| ≤ −2 | −520 | 70 | 0.7 | 0 | `dimPrev` | 1 |

Animate between poses over 950 ms with `CubicBezierEasing(0.22f, 0.8f, 0.24f, 1f)`, using `graphicsLayer` with `cameraDistance = 12 * density`. Brightness is a `ColorMatrix` scale on the card's content.

Layout (dp, from a 390×844 frame; scale with the window width):
- **Top bar:** at the top inset + 8, height 56, a pill with radius 28. It holds the model chip (with download progress), the project chip (flex), and a 44 dp settings button.
- **Status row:** 14 below the top bar. On the left, a 7 dp dot and the phase text (Reading image, Encoding frames, Describing, Paused, Stopped); on the right, "237 of 248 analyzed".
- **Carousel:** 290 tall. Cards are 250×284 with radius 30 and padding 10. The media is 228×196 with radius 20 (Coil, loading the item's thumbnail). Below it: the filename (14 semibold), meta (12 `sub`), then a 16-tick frame strip for videos or a progress bar for photos. A scan line runs over the current item while it is preparing or describing. Done cards get a "Binned"-style badge reading **"Analyzed"**, because files aren't binned in Plan 1.
- **Output card:** radius 28. It shows the DESCRIPTION label with tok/s on the right, the streamed description with a blinking caret, the ACTION list for videos (time chip plus text), and tag chips (`#tag`).
- **Bottom bar:** height 60, radius 30. It holds Stop, Pause/Resume (both 46 dp) and a **Project Media** button with a count badge. That button stays disabled until Plan 2.
- **Ambient backdrop:** three colors taken from the current thumbnail with `androidx.palette:palette-ktx:1.0.0` (add the dependency in this task): vibrant, dominant and muted swatches, falling back to `accent`.

The idle state (no pending items) shows the last analyzed item in the center, with the status "All caught up".

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun poses_matchMockup() {
    assertEquals(CardPose(0f, 0f, 1f, 1f, 1f, 5f), cardPose(0, 5, DarkGlass))
    assertEquals(CardPose(200f, -38f, 0.84f, 1f, 0.55f, 4f), cardPose(1, 5, DarkGlass))
    assertEquals(CardPose(-200f, 38f, 0.84f, 1f, 0.42f, 4f), cardPose(-1, 5, DarkGlass))
    assertEquals(0f, cardPose(2, 5, DarkGlass).alpha); assertEquals(0f, cardPose(-3, 5, DarkGlass).alpha)
    assertEquals(0.82f, cardPose(1, 5, LightGlass).brightness)
}
```

- [ ] **Step 2: Run it and check that it fails.** Expected: FAIL.
- [ ] **Step 3: Implement `cardPose`, the view model and the composables.**
- [ ] **Step 4: Run it and check that it passes.** Expected: PASS.
- [ ] **Step 5: Check on the phone in both themes.** Compare side by side with the mockup artboards "Analyze · Dark" and "Analyze · Light". Take screenshots with `adb exec-out screencap -p > analyze-dark.png` and the light equivalent, and attach them to the task report.
- [ ] **Step 6: Commit** `git commit -am "feat: glass Analyze screen with carousel and streaming output"`

---

### Task 13: End-to-end check on the phone

**Files:**
- Create: `scripts/make-test-media.sh` (ffmpeg clips and stills)
- Modify: `CLAUDE.md` (test media and run notes)

- [ ] **Step 1: Write `scripts/make-test-media.sh`.** It uses `ffmpeg -f lavfi` to create, in `build/test-media/`:
  - `square_red_at_5s.mp4` (10 s, a white square that turns red at 5 s);
  - `long_60s.mp4` (60 s colour bars);
  - `tiny_0_3s.mp4`;
  - `empty.mp4` (0 bytes);
  - three stills (`still_text.jpg` with drawtext "HELLO S26", `still_red_blue.png`, `still_truncated.jpg` = the first 2 KB of a real JPEG).

  Pull one frame of each clip and look at it before trusting the clip (`Instructions.md`).
- [ ] **Step 2: Push the files and scan them in**

Run: `MSYS_NO_PATHCONV=1 adb push build/test-media /sdcard/Movies/avb-plan1 && adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/avb-plan1`

- [ ] **Step 3: Run the scenario** with Do Not Disturb on:
  1. Create project "Plan1 E2E".
  2. Add the folder `Movies/avb-plan1`.
  3. Watch the Analyze screen.
  4. Lock the phone for two minutes, then unlock it.
  5. Add the same folder again.
  6. Kill the app mid-item (`adb shell am force-stop com.agentic.binning`), relaunch it, and resume.

Expected:
- 7 items added; the second add reports "7 already in this project".
- After the force-stop, the interrupted item is analyzed again rather than stuck.
- `empty.mp4` and `still_truncated.jpg` end as failed (decode); the rest end as analyzed.
- Analysis kept going with the screen locked, and the notification showed progress.
- After the last item, logcat shows the model released.
- No file in `Movies/avb-plan1` changed (`adb shell ls -l` before and after match).

- [ ] **Step 4: Inspect the database**

Run: `MSYS_NO_PATHCONV=1 adb pull "/storage/emulated/0/Agentic Binning/Plan1 E2E/.binning/project.db" build/ && sqlite3 build/project.db "select original_name,status from item; select item_id,stop_reason,generated_tokens from analysis;"`
Expected: statuses as above, every `stop_reason` = `eos`, and every `generated_tokens` below its cap.

- [ ] **Step 5: Commit** `git add -A && git commit -m "test: end-to-end analysis scenario and test media script"`

---

## Plans 2 and 3 (written after Plan 1 lands)

- **Plan 2, Sorting and Project Media:**
  - Settings: sorting model (Claude, LFM3-1.7B, Qwen3-8B) and an encrypted API key.
  - A `BatchCompleteHandler` that loads the sorting model, categorizes the batch with consolidation rules checked in code, and releases it.
  - The Project Media screen: grid with thumbnails, FTS search, category chips, secondary filters, detail sheet, select mode, category edit, and Save (database only at that stage).
  - Re-sort project with a model picker and Keep my edits.
- **Plan 3, Binning:**
  - The move engine: plan, copy, verify, delete, a resumable journal, collision suffixes, count verification and MediaStore updates.
  - The Bin Files button, Save moving files that are already binned, and folder rename and merge.
