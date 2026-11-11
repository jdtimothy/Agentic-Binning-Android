package com.agentic.binning.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.projectmedia.ActiveJob
import com.agentic.binning.projectmedia.CategoryChoice
import com.agentic.binning.projectmedia.CategoryNames
import com.agentic.binning.projectmedia.EditResult
import com.agentic.binning.projectmedia.ItemDetail
import com.agentic.binning.projectmedia.MatchedIn
import com.agentic.binning.projectmedia.MediaChip
import com.agentic.binning.projectmedia.MediaCounts
import com.agentic.binning.projectmedia.MediaFilter
import com.agentic.binning.projectmedia.MediaRow
import com.agentic.binning.projectmedia.MediaStatus
import com.agentic.binning.projectmedia.RESORT_BUSY
import com.agentic.binning.projectmedia.ResortRequest
import com.agentic.binning.projectmedia.ResortStart
import com.agentic.binning.projectmedia.activeJobOf
import com.agentic.binning.projectmedia.binFilesBlockedReason
import com.agentic.binning.projectmedia.canTakeACategory
import com.agentic.binning.settings.SortingModelChoice
import com.agentic.binning.ui.settings.SortingOptions
import com.agentic.binning.projectmedia.isWaiting
import com.agentic.binning.projectmedia.matches
import com.agentic.binning.projectmedia.mediaCountsOf
import com.agentic.binning.projectmedia.mediaStatus
import com.agentic.binning.search.FtsQuery
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One open project's media, as Project Media reads it. */
interface MediaSource {
    val projectName: String

    /** Every item, oldest first; with [ftsQuery] (always from [FtsQuery.from]) only the matches. */
    fun rows(ftsQuery: String?): Flow<List<MediaRow>>

    fun categories(): Flow<List<CategoryEntity>>

    /** Whether the last run's folder check found something, so Bin Files stays on to show it (owner, Nov 10). */
    fun folderCheckFailed(): Flow<Boolean> = flowOf(false)

    /** The tile's picture: the thumbnail once it is written, else the file. Called off the main thread. */
    fun image(row: MediaRow): File

    /** The detail sheet's item; null once it is gone. */
    suspend fun detail(id: Long): ItemDetail?

    /** The detail sheet's Save, in one transaction. */
    suspend fun save(request: SaveRequest): EditResult

    /** Bulk Move to category: analyzed items get [category], locked as the user's. */
    suspend fun move(ids: List<Long>, category: CategoryChoice): EditResult

    /** The Category Edit sheet's Save: every rename and merge in one transaction. */
    suspend fun applyCategoryEdits(edits: CategoryEdits): EditResult

    /** Remove from project: the rows go, the files stay (Q1). */
    suspend fun remove(ids: List<Long>)

    /** Retry analysis of a failed item: back to Pending with its attempts reset, and analysis starts (Q19, P6). */
    suspend fun retry(id: Long): EditResult

    /** A failed item's name and category set by hand, both locked; it is then sorted like any other (Q19). */
    suspend fun setByHand(request: SetByHandRequest): EditResult
}

enum class TileBadge { Waiting, Failed, Analyzing }

data class TileUi(
    val id: Long,
    val status: ItemStatus,
    val name: String,
    val image: File,
    /** [image] is the video itself (no thumbnail yet): Coil decodes a frame. */
    val imageIsVideo: Boolean,
    val isVideo: Boolean,
    /** "0:42" on a video with a known length. */
    val duration: String?,
    val badge: TileBadge?,
    /** "Matched in tags" while searching, when the name is not what matched. */
    val matchedText: String?,
    val selected: Boolean,
)

data class ChipUi(
    val chip: MediaChip,
    val label: String,
    val count: Int,
    /** Waiting and Failed: amber, they need attention (Q9). */
    val attention: Boolean,
    /** A category the user made or edited. */
    val locked: Boolean,
    val selected: Boolean,
)

enum class SetFilterKey { Shot, Day }

/** A shot type or date filter that is set: a blue chip with × beside the kind toggle (Q3, option C). */
data class SetFilter(val key: SetFilterKey, val label: String)

data class FilterOption<T>(val value: T?, val label: String, val count: Int, val selected: Boolean)

/** The Filters sheet: Shot type and Date with their counts under the draft, and "Show N items". */
data class FiltersSheet(
    val shots: List<FilterOption<String>>,
    val days: List<FilterOption<LocalDate>>,
    val draftShot: String?,
    val draftDay: LocalDate?,
    val showCount: Int,
)

data class MediaUiState(
    val hasProject: Boolean,
    val projectName: String,
    val itemCount: Int,
    val query: String,
    val categoryCount: Int,
    val chips: List<ChipUi>,
    val kind: ItemKind?,
    val setFilters: List<SetFilter>,
    val resultText: String,
    val tiles: List<TileUi>,
    /** Shown in place of the grid when filters or a search leave nothing. */
    val noMatchText: String?,
    val status: MediaStatus,
    /** Why Bin Files is off, for its toast; null when it is on. */
    val binReason: String?,
    /** Why Re-sort and Categorize now are off: a run is in progress (Q12). */
    val busyReason: String?,
    /** The ticked items in select mode; null outside it. */
    val selection: Set<Long>?,
    val allVisibleSelected: Boolean,
    /** The project has no items: the add-media card replaces the grid. */
    val empty: Boolean,
    val filtersSheet: FiltersSheet?,
    /** The open item's detail sheet (Task 12). */
    val sheet: DetailSheetState?,
    /** The open failed or missing item's sheet (Task 16). */
    val failed: FailedSheetState?,
    /** Select mode's Move to category picker (Task 13). */
    val move: PickerUi?,
    /** Select mode's Remove from project confirmation (Task 13). */
    val removeSelected: RemoveDialogUi?,
    /** The Category Edit sheet (Task 14). */
    val categoryEdit: CategoryEditState?,
    /** The Re-sort sheet (Task 15). */
    val resort: ResortSheetState?,
) {
    companion object {
        val None = MediaUiState(
            hasProject = false, projectName = "", itemCount = 0, query = "", categoryCount = 0, chips = emptyList(),
            kind = null, setFilters = emptyList(), resultText = "", tiles = emptyList(), noMatchText = null,
            status = mediaStatus(MediaCounts(total = 0), null), binReason = null, busyReason = null, selection = null,
            allVisibleSelected = false, empty = false, filtersSheet = null, sheet = null, failed = null, move = null, removeSelected = null,
            categoryEdit = null, resort = null,
        )
    }
}

/** One row of the category picker (mockup state 3b). */
data class PickerOption(
    val id: Long,
    val name: String,
    val description: String,
    val count: Int,
    /** The user's category: sorting never renames or merges it. */
    val locked: Boolean,
    val selected: Boolean,
)

data class PickerUi(
    /** "Choose a category", or "Move N items". */
    val title: String,
    val options: List<PickerOption>,
    /** A new category chosen in this draft, listed (selected) above the others. */
    val pendingNew: String?,
    /** The New category… field is open. */
    val newOpen: Boolean,
    val newName: String,
    /** The clash with an existing name (2a's normalizer), shown under the field. */
    val newError: String?,
    val canAdd: Boolean,
    /** Bulk move only: the footer's "Save · move N"; the detail picker chooses on tap instead. */
    val saveLabel: String?,
    val canSave: Boolean,
    /** Bulk move only: what happens to the files (2b state 4). */
    val note: String? = null,
)

/** What the category picker's rows and buttons do; the detail sheet and bulk move each have one. */
interface PickerActions {
    fun onClose()
    fun onPick(id: Long)
    fun onPickPendingNew()
    fun onNewOpen()
    fun onNewName(text: String)
    fun onAddNew()
    fun onSave()
}

data class RemoveDialogUi(val title: String, val body: String, val removeLabel: String = "Remove")

/** The detail sheet with the picker and the remove dialog stacked over it. */
data class DetailSheetState(
    val ui: DetailUi,
    /** The tile's picture, and whether it is the video itself. */
    val image: File,
    val imageIsVideo: Boolean,
    val picker: PickerUi?,
    val remove: RemoveDialogUi?,
    val error: String?,
    val saving: Boolean,
)

/** The failed item sheet (Task 16) with the picker and the remove dialog stacked over it. */
data class FailedSheetState(
    val ui: FailedUi,
    val image: File,
    val imageIsVideo: Boolean,
    val picker: PickerUi?,
    val remove: RemoveDialogUi?,
    val error: String?,
    val saving: Boolean,
)

/** The Re-sort sheet (Task 15) while it is open; [starting] while the start is being asked for. */
data class ResortSheetState(val ui: ResortUi, val starting: Boolean, val error: String?)

/** What the Re-sort sheet needs from the app: the Settings choice, why a model can't run, and the start (Task 9). */
interface ResortActions {
    suspend fun settingsChoice(): SortingModelChoice

    /** Why [choice] can't run now (no key, not downloaded), or null. */
    suspend fun unavailable(choice: SortingModelChoice): String?

    suspend fun start(request: ResortRequest): ResortStart
}

/** The Category Edit sheet with its staged changes. */
data class CategoryEditState(val ui: CategoryEditUi, val error: String?, val saving: Boolean)

/** What the Category Edit sheet's controls do. */
interface CategoryEditActions {
    fun onClose()
    fun onRenameStart(id: Long)
    fun onRenameText(text: String)
    fun onRenameDone()
    fun onRenameCancel()
    fun onTick(id: Long)
    fun onKeep(id: Long)
    fun onMerge()
    fun onUntick()
    fun onSave()
}

/** Why Edit is off: the sorter matches categories by name while a run is in progress. */
const val EDIT_BUSY = "Edit is available when analysis is stopped or done"

/** A tap on an item that is still waiting to be analyzed. */
const val DETAIL_AFTER_ANALYSIS = "Details open once this item is analyzed"

/** While files move (Bin Files or a Save), every edit waits (2b state 6, Q11). */
const val EDITS_WAIT = "Edits wait until the files have moved"

/** What the user has picked on the screen; reset when the open project changes. */
private data class Picks(
    val query: String = "",
    val chip: MediaChip = MediaChip.All,
    val kind: ItemKind? = null,
    val shot: String? = null,
    val day: LocalDate? = null,
    val selection: Set<Long>? = null,
    /** Non-null while the Filters sheet is open: its draft shot type and day. */
    val draft: Draft? = null,
    /** Non-null while select mode's Move to category picker is open. */
    val move: MoveSession? = null,
    val confirmBulkRemove: Boolean = false,
)

/** The bulk move picker: the category chosen so far and the New category… field. */
private data class MoveSession(
    val pick: CategoryPick? = null,
    val picker: PickerSession = PickerSession(),
    val saving: Boolean = false,
)

private data class Draft(val shot: String?, val day: LocalDate?)

/**
 * An open detail sheet, or a failed item's sheet when the item is failed or missing: the item as
 * loaded, the draft, and what is stacked over it.
 */
private data class DetailSession(
    val detail: ItemDetail,
    val image: File,
    val imageIsVideo: Boolean,
    val draft: DetailDraft,
    /** The failed item sheet's Set name and category by hand form is open. */
    val manual: Boolean = false,
    val picker: PickerSession? = null,
    val confirmRemove: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

private data class PickerSession(val newOpen: Boolean = false, val newName: String = "")

private data class ResortSession(
    val settingsChoice: SortingModelChoice,
    val chosen: SortingModelChoice,
    val unavailable: Map<SortingModelChoice, String>,
    val keepEdits: Boolean = true,
    val starting: Boolean = false,
    val error: String? = null,
)

/** Every sheet session, combined so the state stays one `combine`. */
private data class Sheets(val detail: DetailSession?, val categoryEdit: CategoryEditSession?, val resort: ResortSession?)

private data class CategoryEditSession(
    val draft: CategoryEditDraft = CategoryEditDraft(),
    val error: String? = null,
    val saving: Boolean = false,
)

/** The rows of one source with what is needed to show them. */
private data class Loaded(
    val source: MediaSource?,
    val all: List<MediaRow>,
    val found: List<MediaRow>,
    val searched: Boolean,
    val categories: List<CategoryEntity>,
    val images: Map<Long, File>,
    /** The last run's folder check found something. */
    val checkFolders: Boolean = false,
)

/**
 * Project Media (plan 2b Task 11): search, the category chips, the kind toggle and Filters sheet,
 * select mode and the dock. Search text is debounced ([searchDebounceMs]) and always goes through
 * [FtsQuery.from]; every other filter is applied here with [MediaFilter.matches], so the counts in
 * the chips and the Filters sheet agree with the grid. [scope] is for tests; the app uses the
 * view model's own.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ProjectMediaViewModel(
    source: Flow<MediaSource?>,
    phase: Flow<PipelinePhase?>,
    private val onCategorizeNow: () -> Unit,
    /** Bin Files, when it is on: the confirm sheet (Plan 3). */
    private val onBinFiles: () -> Unit = {},
    /** The move in progress (Plan 3), for the status line; null when no files move. */
    move: Flow<MoveProgress?> = flowOf(null),
    private val resort: ResortActions? = null,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMs: Long = 150,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val picks = MutableStateFlow(Picks())
    private val phaseNow = phase.distinctUntilChanged().stateIn(this.scope, SharingStarted.Eagerly, null)
    private val moveNow = move.distinctUntilChanged().stateIn(this.scope, SharingStarted.Eagerly, null)
    private val session = MutableStateFlow<DetailSession?>(null)
    private val categorySession = MutableStateFlow<CategoryEditSession?>(null)
    private val resortSession = MutableStateFlow<ResortSession?>(null)

    /** The shown project's categories, for staging category edits between frames. */
    @Volatile
    private var latestCategories: List<CategoryEntity> = emptyList()

    /** The project shown now, for loads and edits. */
    @Volatile
    private var current: MediaSource? = null
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Toast lines: why a disabled action is off, or what comes later. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** False until the first project is shown: only a later switch resets the screen. */
    private var shownOne = false

    private val sources = source.distinctUntilChanged()
        // Another project starts from a clean screen; the first keeps what was picked before it loaded.
        .onEach {
            if (shownOne) {
                picks.value = Picks()
                session.value = null
                categorySession.value = null
                resortSession.value = null
            }
            shownOne = true
            current = it
        }

    /** The search actually sent: debounced while typing, at once when cleared. */
    private val fts: Flow<String?> = picks.map { it.query }.distinctUntilChanged()
        .debounce { if (it.isBlank()) 0L else searchDebounceMs }
        .map { FtsQuery.from(it) }
        .distinctUntilChanged()

    private val loaded: Flow<Loaded> = sources.flatMapLatest { src ->
        if (src == null) {
            flowOf(Loaded(null, emptyList(), emptyList(), false, emptyList(), emptyMap()))
        } else {
            val all = src.rows(null)
            val found = fts.flatMapLatest { q -> if (q == null) all.map { it to false } else src.rows(q).map { it to true } }
            combine(all, found, src.categories(), src.folderCheckFailed()) { a, (f, searched), c, check ->
                Loaded(src, a, f, searched, c, f.associate { it.id to src.image(it) }, check)
            }.flowOn(io).onEach { latestCategories = it.categories }
        }
    }

    val state: StateFlow<MediaUiState> =
        combine(loaded, picks, combine(phaseNow, moveNow, ::Pair), combine(session, categorySession, resortSession, ::Sheets)) { l, p, ph, s ->
            build(l, p, ph.first, ph.second, s)
        }
        .stateIn(this.scope, SharingStarted.Eagerly, MediaUiState.None)

    fun onQuery(text: String) = picks.update { it.copy(query = text) }

    fun onClearQuery() = picks.update { it.copy(query = "") }

    /** Tapping the selected chip again goes back to All. */
    fun onChip(chip: MediaChip) = picks.update { it.copy(chip = if (it.chip == chip) MediaChip.All else chip) }

    fun onKind(kind: ItemKind?) = picks.update { it.copy(kind = kind) }

    fun onClearFilter(key: SetFilterKey) = picks.update {
        when (key) {
            SetFilterKey.Shot -> it.copy(shot = null)
            SetFilterKey.Day -> it.copy(day = null)
        }
    }

    fun openFilters() = picks.update { it.copy(draft = Draft(it.shot, it.day)) }

    fun onDraftShot(shot: String?) = picks.update { p -> p.draft?.let { p.copy(draft = it.copy(shot = shot)) } ?: p }

    fun onDraftDay(day: LocalDate?) = picks.update { p -> p.draft?.let { p.copy(draft = it.copy(day = day)) } ?: p }

    /** "Show N items": the draft becomes the filter. */
    fun applyFilters() = picks.update { p -> p.draft?.let { p.copy(shot = it.shot, day = it.day, draft = null) } ?: p }

    /** Closing the sheet any other way drops its draft. */
    fun closeFilters() = picks.update { it.copy(draft = null) }

    /**
     * In select mode a tap ticks or unticks. Otherwise it opens the item's detail sheet, or for a
     * failed or missing item the failed item sheet; an item not analyzed yet says so.
     */
    fun onTileClick(id: Long) {
        val p = picks.value
        if (p.selection != null) {
            picks.update { it.copy(selection = it.selection?.toggle(id)) }
            return
        }
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return
        when {
            opensDetail(tile.status) || opensFailedSheet(tile.status) -> openDetail(tile)
            else -> _messages.tryEmit(DETAIL_AFTER_ANALYSIS)
        }
    }

    private fun openDetail(tile: TileUi) {
        val src = current ?: return
        scope.launch {
            val d = withContext(io) { src.detail(tile.id) } ?: return@launch
            if (current === src) session.value = DetailSession(d, tile.image, tile.imageIsVideo, DetailDraft.of(d))
        }
    }

    private fun updateSession(block: (DetailSession) -> DetailSession) = session.update { it?.let(block) }

    /**
     * Applies a write's result to the sheet that asked for it, only while that sheet is still open:
     * a slow write never closes or marks another item's sheet opened meanwhile. Null closes it.
     */
    private fun settle(detail: ItemDetail, block: (DetailSession) -> DetailSession?) =
        session.update { s -> if (s != null && s.detail === detail) block(s) else s }

    /** A detail or by-hand Save's result, for the sheet of [detail]. */
    private fun settleSave(detail: ItemDetail, result: EditResult) = when (result) {
        EditResult.Ok, is EditResult.Created -> settle(detail) { null }
        is EditResult.Clash -> settle(detail) { it.copy(saving = false, error = "A category named “${result.existing}” already exists.") }
        EditResult.InvalidName -> settle(detail) { it.copy(saving = false, error = "That category name can't be used.") }
        is EditResult.Busy -> settle(detail) { it.copy(saving = false, error = result.reason) }
        EditResult.NotFound -> {
            settle(detail) { null }
            _messages.tryEmit("This item is no longer in the project")
        }
    }

    private fun updateDraft(block: (DetailDraft) -> DetailDraft) = updateSession { it.copy(draft = block(it.draft), error = null) }

    fun onDetailName(text: String) = updateDraft { it.copy(name = text) }

    /** Unlock, or Undo an unlock (Q5, P2). */
    fun onToggleUnlockName() = updateDraft { it.copy(unlockName = !it.unlockName) }

    fun onToggleUnlockCategory() = updateDraft { it.copy(unlockCategory = !it.unlockCategory) }

    /** Cancel or closing drops the sheet's edits (Q6). */
    fun onCloseDetail() {
        session.value = null
    }

    fun onOpenPicker() = updateSession { it.copy(picker = PickerSession()) }

    fun onClosePicker() = updateSession { it.copy(picker = null) }

    /** The picker over whichever item sheet is open. */
    private fun openPicker(): PickerUi? = state.value.let { it.sheet?.picker ?: it.failed?.picker }

    fun onPickCategory(id: Long) {
        val name = openPicker()?.options?.firstOrNull { it.id == id }?.name ?: return
        updateSession { it.copy(draft = it.draft.copy(pick = CategoryPick.Existing(id, name)), picker = null, error = null) }
    }

    /** Keeps the new category already chosen in this draft. */
    fun onPickPendingNew() = onClosePicker()

    fun onPickerNewOpen() = updateSession { s -> s.copy(picker = (s.picker ?: PickerSession()).copy(newOpen = true)) }

    fun onPickerNewName(text: String) = updateSession { s -> s.copy(picker = s.picker?.copy(newName = text)) }

    fun onPickerAddNew() {
        val picker = openPicker() ?: return
        if (!picker.canAdd) return
        val name = CategoryNames.clean(picker.newName)
        updateSession { it.copy(draft = it.draft.copy(pick = CategoryPick.New(name)), picker = null, error = null) }
    }

    fun onSaveDetail() {
        if (refuseWhileMoving()) return
        val s = session.value ?: return
        val src = current ?: return
        if (s.saving || !detailUi(s.detail, s.draft, zone).saveEnabled) return
        val request = saveRequest(s.detail, s.draft)
        session.value = s.copy(saving = true, error = null)
        scope.launch {
            val result = withContext(io) { src.save(request) }
            if (current !== src) return@launch
            settleSave(s.detail, result)
        }
    }

    /** The detail sheet's picker: a tap chooses and closes. */
    val detailPicker: PickerActions = object : PickerActions {
        override fun onClose() = onClosePicker()
        override fun onPick(id: Long) = onPickCategory(id)
        override fun onPickPendingNew() = this@ProjectMediaViewModel.onPickPendingNew()
        override fun onNewOpen() = onPickerNewOpen()
        override fun onNewName(text: String) = onPickerNewName(text)
        override fun onAddNew() = onPickerAddNew()
        override fun onSave() = Unit
    }

    /**
     * The failed item sheet's Retry analysis (Q19): the sheet closes and the tile shows the new run.
     * A paused run picks the item up only after Resume, and the toast says so.
     */
    fun onRetryFailed() {
        if (refuseWhileMoving()) return
        val s = session.value ?: return
        val src = current ?: return
        if (s.saving) return
        session.value = s.copy(saving = true, error = null)
        scope.launch {
            val result = withContext(io) { src.retry(s.detail.item.id) }
            if (current !== src) return@launch
            settle(s.detail) { null }
            val file = s.detail.item.originalName
            _messages.tryEmit(
                when {
                    result != EditResult.Ok -> "This item is no longer in the project"
                    phaseNow.value == PipelinePhase.Paused -> "$file will be analyzed again when you resume"
                    else -> "Analyzing $file again"
                },
            )
        }
    }

    /** Opens the by-hand form, starting from the item as loaded; not while a Retry is being sent. */
    fun onSetByHand() = updateSession {
        if (it.saving) it else it.copy(manual = true, draft = DetailDraft.of(it.detail), picker = null, error = null)
    }

    /** Back from the by-hand form drops what was typed. */
    fun onSetByHandBack() = updateSession { it.copy(manual = false, draft = DetailDraft.of(it.detail), picker = null, error = null) }

    fun onSaveByHand() {
        if (refuseWhileMoving()) return
        val s = session.value ?: return
        val src = current ?: return
        val request = setByHandRequest(s.detail, s.draft) ?: return
        if (s.saving || !s.manual) return
        session.value = s.copy(saving = true, error = null)
        scope.launch {
            val result = withContext(io) { src.setByHand(request) }
            if (current !== src) return@launch
            settleSave(s.detail, result)
        }
    }

    /** Asks before removing; not while the sheet is sending a Save or Retry. */
    fun onRemoveDetail() = updateSession { if (it.saving) it else it.copy(confirmRemove = true) }

    fun onCancelRemove() = updateSession { it.copy(confirmRemove = false) }

    fun onConfirmRemove() {
        if (refuseWhileMoving()) return
        val s = session.value ?: return
        val src = current ?: return
        session.value = null
        scope.launch { withContext(io) { src.remove(listOf(s.detail.item.id)) } }
    }

    fun onTileLongPress(id: Long) {
        if (refuseWhileMoving()) return
        picks.update { p -> p.copy(selection = (p.selection ?: emptySet()).let { if (id in it) it else it + id }) }
    }

    fun onStartSelect() {
        if (refuseWhileMoving()) return
        picks.update { it.copy(selection = it.selection ?: emptySet()) }
    }

    fun onExitSelect() = picks.update { it.copy(selection = null, move = null, confirmBulkRemove = false) }

    /** The ticked items in tick order (a removed one is skipped by the edit itself). */
    private fun selectedIds(): List<Long> = picks.value.selection?.toList().orEmpty()

    /** Move to category: opens the picker for the ticked items (off with none ticked). */
    fun onBulkMove() {
        if (selectedIds().isEmpty()) return
        picks.update { it.copy(move = MoveSession()) }
    }

    private fun updateMove(block: (MoveSession) -> MoveSession) = picks.update { p -> p.move?.let { p.copy(move = block(it)) } ?: p }

    /** Select mode's move picker: a tap chooses; "Save · move N" moves. */
    val movePicker: PickerActions = object : PickerActions {
        override fun onClose() = picks.update { it.copy(move = null) }

        override fun onPick(id: Long) {
            val name = state.value.move?.options?.firstOrNull { it.id == id }?.name ?: return
            updateMove { it.copy(pick = CategoryPick.Existing(id, name)) }
        }

        override fun onPickPendingNew() = Unit

        override fun onNewOpen() = updateMove { it.copy(picker = it.picker.copy(newOpen = true)) }

        override fun onNewName(text: String) = updateMove { it.copy(picker = it.picker.copy(newName = text)) }

        override fun onAddNew() {
            val ui = state.value.move ?: return
            if (!ui.canAdd) return
            val name = CategoryNames.clean(ui.newName)
            updateMove { it.copy(pick = CategoryPick.New(name), picker = PickerSession()) }
        }

        override fun onSave() = saveMove()
    }

    private fun saveMove() {
        if (refuseWhileMoving()) return
        val move = picks.value.move ?: return
        val src = current ?: return
        val ids = selectedIds()
        val choice = when (val pick = move.pick) {
            is CategoryPick.Existing -> CategoryChoice.Existing(pick.id)
            is CategoryPick.New -> CategoryChoice.New(pick.name)
            null -> return
        }
        if (move.saving || ids.isEmpty()) return
        val statuses = state.value.tiles.associate { it.id to it.status }
        val left = ids.count { id -> statuses[id]?.let { !canTakeACategory(it) } == true }
        updateMove { it.copy(saving = true) }
        scope.launch {
            val result = withContext(io) { src.move(ids, choice) }
            if (current !== src) return@launch
            when (result) {
                EditResult.Ok, is EditResult.Created -> {
                    picks.update { it.copy(selection = null, move = null) }
                    if (left > 0) {
                        val moved = ids.size - left
                        _messages.tryEmit("Moved ${countOf(moved, "item")}. $left not analyzed yet stayed where ${if (left == 1) "it was" else "they were"}.")
                    }
                }
                is EditResult.Clash -> {
                    updateMove { it.copy(saving = false) }
                    _messages.tryEmit("A category named “${result.existing}” already exists.")
                }
                EditResult.InvalidName, EditResult.NotFound -> {
                    updateMove { it.copy(saving = false) }
                    _messages.tryEmit("Couldn't move the items; try again")
                }
                is EditResult.Busy -> {
                    updateMove { it.copy(saving = false) }
                    _messages.tryEmit(result.reason)
                }
            }
        }
    }

    fun onBulkRemove() {
        if (selectedIds().isEmpty()) return
        picks.update { it.copy(confirmBulkRemove = true) }
    }

    fun onCancelBulkRemove() = picks.update { it.copy(confirmBulkRemove = false) }

    fun onConfirmBulkRemove() {
        val src = current ?: return
        val ids = selectedIds()
        picks.update { it.copy(selection = null, confirmBulkRemove = false, move = null) }
        if (ids.isNotEmpty()) scope.launch { withContext(io) { src.remove(ids) } }
    }

    /** Select all ticks every visible tile; when they all are, it unticks them (Clear). */
    fun onToggleSelectAll() {
        val visible = state.value.tiles.map { it.id }
        picks.update { p ->
            val sel = state.value.selection ?: return@update p
            p.copy(selection = if (visible.all { it in sel }) sel - visible.toSet() else sel + visible)
        }
    }

    fun onCategorizeNow() {
        val busy = state.value.busyReason
        if (busy != null) _messages.tryEmit(busy) else onCategorizeNow.invoke()
    }

    /** Edit on the categories row: the Category Edit sheet, off while a run is in progress. */
    fun onOpenCategoryEdit() {
        state.value.busyReason?.let {
            _messages.tryEmit(if (it == EDITS_WAIT) it else EDIT_BUSY)
            return
        }
        categorySession.value = CategoryEditSession()
    }

    private fun updateCategoryDraft(block: (CategoryEditDraft) -> CategoryEditDraft) =
        categorySession.update { s -> s?.copy(draft = block(s.draft), error = null) }

    val categoryEdit: CategoryEditActions = object : CategoryEditActions {
        override fun onClose() {
            categorySession.value = null
        }

        override fun onRenameStart(id: Long) = updateCategoryDraft { it.startRename(id, latestCategories) }
        override fun onRenameText(text: String) = updateCategoryDraft { it.editTo(text) }
        override fun onRenameDone() = updateCategoryDraft { it.finishRename(latestCategories) }
        override fun onRenameCancel() = updateCategoryDraft { it.cancelRename() }
        override fun onTick(id: Long) = updateCategoryDraft { it.tick(id) }
        override fun onKeep(id: Long) = updateCategoryDraft { it.keep(id) }
        override fun onMerge() = updateCategoryDraft { it.merge() }
        override fun onUntick() = updateCategoryDraft { it.untick() }
        override fun onSave() = saveCategoryEdits()
    }

    private fun saveCategoryEdits() {
        val s = categorySession.value ?: return
        val src = current ?: return
        val edits = categoryEditsOf(latestCategories, s.draft)
        if (s.saving || edits.count == 0) return
        if (state.value.busyReason != null) {
            _messages.tryEmit(EDIT_BUSY)
            return
        }
        categorySession.value = s.copy(saving = true, error = null)
        scope.launch {
            val result = withContext(io) { src.applyCategoryEdits(edits) }
            if (current !== src) return@launch
            when (result) {
                EditResult.Ok, is EditResult.Created -> categorySession.value = null
                is EditResult.Clash -> categorySession.update { it?.copy(saving = false, error = "A category named “${result.existing}” already exists.") }
                EditResult.InvalidName -> categorySession.update { it?.copy(saving = false, error = "A category name can't be empty.") }
                EditResult.NotFound -> categorySession.update { it?.copy(saving = false, error = "A category changed meanwhile. Close and try again.") }
                is EditResult.Busy -> categorySession.update { it?.copy(saving = false, error = result.reason) }
            }
        }
    }

    /**
     * Re-sort on the categories row: off with the reason while a run is in progress (Q12), else the
     * sheet on the Settings choice with Keep my edits ticked (Q4, Q14).
     */
    fun onResort() {
        state.value.busyReason?.let {
            _messages.tryEmit(it)
            return
        }
        val actions = resort ?: return
        val src = current ?: return
        scope.launch {
            val choice = actions.settingsChoice()
            val unavailable = SortingOptions.mapNotNull { o -> actions.unavailable(o.choice)?.let { o.choice to it } }.toMap()
            if (current === src) resortSession.value = ResortSession(choice, choice, unavailable)
        }
    }

    /** Picks the model for this run and checks again whether it can run (a download may have finished). */
    fun onResortModel(choice: SortingModelChoice) {
        resortSession.update { it?.copy(chosen = choice, error = null) }
        val actions = resort ?: return
        val src = current ?: return
        scope.launch {
            val reason = actions.unavailable(choice)
            if (current !== src) return@launch
            resortSession.update { r -> r?.copy(unavailable = if (reason == null) r.unavailable - choice else r.unavailable + (choice to reason)) }
        }
    }

    fun onResortKeepEdits() = resortSession.update { it?.copy(keepEdits = !it.keepEdits, error = null) }

    fun onCloseResort() {
        resortSession.value = null
    }

    /** "Re-sort N items": asks Task 9's start; a refusal keeps the sheet with its reason. */
    fun onRunResort() {
        val s = resortSession.value ?: return
        val ui = state.value.resort?.ui ?: return
        val actions = resort ?: return
        val src = current ?: return
        if (s.starting || !ui.enabled) return
        resortSession.value = s.copy(starting = true, error = null)
        scope.launch {
            val result = withContext(io) { actions.start(ResortRequest(s.chosen, s.keepEdits)) }
            if (current !== src) return@launch
            when (result) {
                ResortStart.Started -> {
                    resortSession.value = null
                    _messages.tryEmit("${ui.buttonLabel.replace("Re-sort", "Re-sorting")} with ${modelName(s.chosen)}")
                }
                is ResortStart.Refused -> resortSession.update { it?.copy(starting = false, error = result.reason) }
            }
        }
    }

    /** True (and says why) while files move: every Save, Remove and Retry waits (2b state 6, Q11). */
    private fun refuseWhileMoving(): Boolean {
        if (phaseNow.value?.isMoving != true) return false
        _messages.tryEmit(EDITS_WAIT)
        return true
    }

    fun onBinFiles() {
        val reason = state.value.binReason
        if (reason != null) _messages.tryEmit(reason) else onBinFiles.invoke()
    }

    /**
     * A row of a Bin Files sheet: opens the item's sheet, whatever the filters show. The file may have
     * moved or gone, so its picture comes from where it is now.
     */
    fun onOpenItem(id: Long) {
        val src = current ?: return
        scope.launch {
            val d = withContext(io) { src.detail(id) } ?: return@launch
            if (current === src) {
                session.value = DetailSession(d, File(d.item.currentPath), d.item.kind == ItemKind.Video, DetailDraft.of(d))
            }
        }
    }

    private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

    private fun build(l: Loaded, p: Picks, phase: PipelinePhase?, move: MoveProgress?, sheets: Sheets): MediaUiState {
        val src = l.source ?: return MediaUiState.None
        val d = sheets.detail
        val c = sheets.categoryEdit
        val job = activeJobOf(phase, move)
        val counts = mediaCountsOf(l.all, { it.status }) { isWaiting(it.status, it.categoryId, it.categorySetByUser) }
        val filter = filterOf(p.chip, p.kind, p.shot, p.day)
        // The base of the chip counts: everything but the chip.
        val base = l.found.filter(filter.copy(chip = MediaChip.All)::matches)
        val visible = base.filter(filter::matches)
        val query = if (l.searched) p.query.trim() else ""

        val chips = buildList {
            add(ChipUi(MediaChip.All, "All", base.size, attention = false, locked = false, selected = p.chip == MediaChip.All))
            val waiting = base.count { isWaiting(it.status, it.categoryId, it.categorySetByUser) }
            if (waiting > 0 || p.chip == MediaChip.Waiting) {
                add(ChipUi(MediaChip.Waiting, "Waiting", waiting, attention = true, locked = false, selected = p.chip == MediaChip.Waiting))
            }
            val failed = base.count { it.status == ItemStatus.Failed }
            if (failed > 0 || p.chip == MediaChip.Failed) {
                add(ChipUi(MediaChip.Failed, "Failed", failed, attention = true, locked = false, selected = p.chip == MediaChip.Failed))
            }
            val byCategory = base.groupingBy { it.categoryId }.eachCount()
            for (c in l.categories) {
                val chip = MediaChip.Category(c.id)
                val n = byCategory[c.id] ?: 0
                // While searching, categories with no matches drop out of the row.
                if (l.searched && n == 0 && p.chip != chip) continue
                add(ChipUi(chip, c.name, n, attention = false, locked = c.createdBy == "user", selected = p.chip == chip))
            }
        }

        val chipName = when (val c = p.chip) {
            MediaChip.All -> "All categories"
            MediaChip.Waiting -> "Waiting to be categorized"
            MediaChip.Failed -> "Failed"
            is MediaChip.Category -> l.categories.firstOrNull { it.id == c.id }?.name ?: "Category"
        }
        val resultText = if (l.searched) {
            "${visible.size} ${if (visible.size == 1) "result" else "results"} for “$query”" +
                (if (p.chip != MediaChip.All) " in $chipName" else "")
        } else {
            "$chipName · ${visible.size} ${if (visible.size == 1) "item" else "items"}"
        }

        // Ticked items that were removed meanwhile drop out of the selection.
        val ids = l.all.mapTo(HashSet()) { it.id }
        val selection = p.selection?.filterTo(LinkedHashSet()) { it in ids }
        val tiles = visible.map { row ->
            val image = l.images[row.id] ?: File(row.currentPath)
            TileUi(
                id = row.id,
                status = row.status,
                name = tileName(row),
                image = image,
                imageIsVideo = row.kind == ItemKind.Video && image.path == File(row.currentPath).path,
                isVideo = row.kind == ItemKind.Video,
                duration = row.durationMs?.takeIf { row.kind == ItemKind.Video }?.let(::durationText),
                badge = when {
                    row.status == ItemStatus.Failed -> TileBadge.Failed
                    row.status == ItemStatus.Analyzing -> TileBadge.Analyzing
                    isWaiting(row.status, row.categoryId, row.categorySetByUser) -> TileBadge.Waiting
                    else -> null
                },
                matchedText = row.matchedIn?.takeIf { l.searched && it != MatchedIn.Name }?.let { "Matched in ${matchedLabel(it)}" },
                selected = selection != null && row.id in selection,
            )
        }

        val empty = l.all.isEmpty()
        val noMatch = when {
            empty || tiles.isNotEmpty() -> null
            l.searched -> "Nothing in ${src.projectName} matches “$query”."
            else -> "No items match these filters."
        }

        val setFilters = listOfNotNull(
            p.shot?.let { SetFilter(SetFilterKey.Shot, shotLabel(it)) },
            p.day?.let { SetFilter(SetFilterKey.Day, dayLabel(it, l.all)) },
        )

        val sheet = p.draft?.let { d ->
            // Each choice counts what the grid would show with it and the rest of the draft.
            fun count(shot: String?, day: LocalDate?) = l.found.count(filterOf(p.chip, p.kind, shot, day)::matches)
            val shotsPresent = SHOT_ORDER.filter { s -> l.all.any { it.shotType == s } }
            val daysPresent = l.all.mapNotNull { it.takenAt?.let(::dayOf) }.distinct().sorted()
            FiltersSheet(
                shots = listOf(FilterOption<String>(null, "Any shot type", count(null, d.day), d.shot == null)) +
                    shotsPresent.map { FilterOption(it, shotLabel(it), count(it, d.day), d.shot == it) },
                days = listOf(FilterOption<LocalDate>(null, "Any date", count(d.shot, null), d.day == null)) +
                    daysPresent.map { FilterOption(it, dayLabel(it, l.all), count(d.shot, it), d.day == it) },
                draftShot = d.shot,
                draftDay = d.day,
                showCount = count(d.shot, d.day),
            )
        }

        return MediaUiState(
            hasProject = true,
            projectName = src.projectName,
            itemCount = l.all.size,
            query = p.query,
            categoryCount = l.categories.size,
            chips = chips,
            kind = p.kind,
            setFilters = setFilters,
            resultText = resultText,
            tiles = tiles,
            noMatchText = noMatch,
            status = mediaStatus(counts, job, l.checkFolders),
            binReason = binFilesBlockedReason(counts, job, l.checkFolders),
            busyReason = when (job) {
                null -> null
                is ActiveJob.Binning, is ActiveJob.Saving -> EDITS_WAIT
                else -> RESORT_BUSY
            },
            selection = selection,
            allVisibleSelected = selection != null && tiles.isNotEmpty() && tiles.all { it.id in selection },
            empty = empty,
            filtersSheet = sheet,
            sheet = d?.takeUnless { opensFailedSheet(it.detail.item.status) }?.let { detailSheet(it, l, src.projectName) },
            failed = d?.takeIf { opensFailedSheet(it.detail.item.status) }?.let { failedSheet(it, l, src.projectName) },
            move = p.move?.takeIf { selection != null }?.let { m ->
                val n = selection.orEmpty().size
                pickerUi(
                    title = "Move ${countOf(n, "item")}",
                    chosen = (m.pick as? CategoryPick.Existing)?.id,
                    pendingNew = (m.pick as? CategoryPick.New)?.name,
                    session = m.picker,
                    l = l,
                    saveLabel = "Save · move $n",
                    canSave = m.pick != null && n > 0 && !m.saving,
                    note = bulkMoveNote(selection.orEmpty(), l),
                )
            },
            removeSelected = if (p.confirmBulkRemove && !selection.isNullOrEmpty()) bulkRemoveDialog(selection, l, src.projectName) else null,
            categoryEdit = c?.let {
                val counts = l.all.mapNotNull { row -> row.categoryId }.groupingBy { id -> id }.eachCount()
                val binned = l.all.filter { row -> row.status == ItemStatus.Binned }.mapNotNull { row -> row.categoryId }.groupingBy { id -> id }.eachCount()
                CategoryEditState(categoryEditUi(l.categories, counts, it.draft, binned), it.error, it.saving)
            },
            resort = sheets.resort?.let { r ->
                // What prepareResort hands the sorter: analyzed items waiting, sorted or binned (B10), less
                // those whose category the user set when Keep my edits is ticked.
                val resortable = l.all.filter { it.hasAnalysis && (it.status == ItemStatus.Analyzed || it.status == ItemStatus.Sorted || it.status == ItemStatus.Binned) }
                val resortCounts = ResortCounts(
                    withEditsKept = resortable.count { !it.categorySetByUser },
                    withoutEdits = resortable.size,
                    failed = l.all.count { it.status == ItemStatus.Failed },
                    myCategories = l.categories.count { it.createdBy == "user" },
                    myItems = l.all.count { it.nameSetByUser || it.categorySetByUser },
                )
                ResortSheetState(
                    resortUi(resortCounts, r.settingsChoice, r.chosen, r.keepEdits, r.unavailable, busy = job != null),
                    r.starting,
                    r.error,
                )
            },
        )
    }

    private fun bulkMoveNote(ids: Set<Long>, l: Loaded): String {
        val binned = l.all.count { it.id in ids && it.status == ItemStatus.Binned }
        val files = when (binned) {
            0 -> "Only the database changes now. The files move at the next Bin Files."
            1 -> "1 of them is binned; its file moves right away. The others move at the next Bin Files."
            else -> "$binned of them are binned; their files move right away. The others move at the next Bin Files."
        }
        return "$files Moved items count as set by you."
    }

    /** ", in the Beach folder" for a binned file: Remove never moves it (Q2). */
    private fun whereItStays(status: ItemStatus, path: String) =
        if (status == ItemStatus.Binned) ", in the ${path.substringBeforeLast('/').substringAfterLast('/')} folder" else ""

    private fun bulkRemoveDialog(ids: Set<Long>, l: Loaded, projectName: String): RemoveDialogUi {
        if (ids.size == 1) {
            val row = l.all.firstOrNull { it.id == ids.first() }
            val name = row?.let(::tileName) ?: "this item"
            val where = row?.let { whereItStays(it.status, it.currentPath) }.orEmpty()
            return RemoveDialogUi(
                title = "Remove “$name” from $projectName?",
                body = "The file stays on your phone where it is now$where. The project forgets its analysis, name and category. " +
                    "You can add it again later.",
            )
        }
        val binned = l.all.count { it.id in ids && it.status == ItemStatus.Binned }
        val inFolders = when (binned) {
            0 -> ""
            1 -> "1 of them is in a category folder and stays there. "
            else -> "$binned of them are in a category folder and stay there. "
        }
        return RemoveDialogUi(
            title = "Remove ${ids.size} items from $projectName?",
            body = "The files stay on your phone where they are now. The project forgets their analysis, names and categories. " +
                inFolders + "You can add them again later.",
            removeLabel = "Remove ${ids.size}",
        )
    }

    private fun pickerUi(
        title: String,
        chosen: Long?,
        pendingNew: String?,
        session: PickerSession,
        l: Loaded,
        saveLabel: String?,
        canSave: Boolean,
        note: String? = null,
    ): PickerUi {
        val byCategory = l.all.groupingBy { it.categoryId }.eachCount()
        val clean = CategoryNames.clean(session.newName)
        val clash = if (clean.isEmpty()) null else CategoryNames.clash(clean, l.categories.map { it.name })
        return PickerUi(
            title = title,
            options = l.categories.map { c ->
                PickerOption(c.id, c.name, c.description, byCategory[c.id] ?: 0, locked = c.createdBy == "user", selected = c.id == chosen)
            },
            pendingNew = pendingNew,
            newOpen = session.newOpen,
            newName = session.newName,
            newError = clash?.let { "That is the same as “$it”. Pick it from the list instead." },
            canAdd = clean.isNotEmpty() && clash == null,
            saveLabel = saveLabel,
            canSave = canSave,
            note = note,
        )
    }

    /** The item sheet's category picker: the draft's choice, else the item's category. */
    private fun sessionPicker(d: DetailSession, l: Loaded): PickerUi? = d.picker?.let { ps ->
        val chosen = when (val pick = d.draft.pick) {
            is CategoryPick.Existing -> pick.id
            is CategoryPick.New -> null
            null -> d.detail.item.categoryId
        }
        pickerUi("Choose a category", chosen, (d.draft.pick as? CategoryPick.New)?.name, ps, l, saveLabel = null, canSave = false)
    }

    private fun sessionRemove(d: DetailSession, projectName: String): RemoveDialogUi? {
        if (!d.confirmRemove) return null
        val item = d.detail.item
        val name = item.friendlyName?.takeIf { it.isNotBlank() } ?: item.originalName
        return RemoveDialogUi(
            title = "Remove “$name” from $projectName?",
            body = "The file stays on your phone where it is now${whereItStays(item.status, item.currentPath)}. The project forgets its analysis, name and category. " +
                "You can add it again later.",
        )
    }

    private fun detailSheet(d: DetailSession, l: Loaded, projectName: String) = DetailSheetState(
        ui = detailUi(d.detail, d.draft, zone),
        image = d.image,
        imageIsVideo = d.imageIsVideo,
        picker = sessionPicker(d, l),
        remove = sessionRemove(d, projectName),
        error = d.error,
        saving = d.saving,
    )

    private fun failedSheet(d: DetailSession, l: Loaded, projectName: String) = FailedSheetState(
        ui = failedUi(d.detail, d.draft, d.manual, zone),
        image = d.image,
        imageIsVideo = d.imageIsVideo,
        picker = sessionPicker(d, l),
        remove = sessionRemove(d, projectName),
        error = d.error,
        saving = d.saving,
    )

    private fun filterOf(chip: MediaChip, kind: ItemKind?, shot: String?, day: LocalDate?): MediaFilter {
        val from = day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val to = day?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1)
        return MediaFilter(chip = chip, kind = kind, shotType = shot, takenFrom = from, takenTo = to)
    }

    private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    /** "Jul 14"; with the year when the project spans more than one. */
    private fun dayLabel(day: LocalDate, rows: List<MediaRow>): String {
        val years = rows.mapNotNull { it.takenAt?.let(::dayOf)?.year }.toSet()
        return day.format(if (years.size > 1) DAY_WITH_YEAR else DAY)
    }

    companion object {
        /** The analysis prompt's shot types, in the sheet's order. */
        private val SHOT_ORDER = listOf("wide", "medium", "close")
        private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        private val DAY_WITH_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

        fun shotLabel(shot: String): String = when (shot) {
            "wide" -> "Wide"
            "medium" -> "Medium"
            "close" -> "Close-up"
            else -> shot.replaceFirstChar { it.uppercase() }
        }

        private fun matchedLabel(m: MatchedIn) = when (m) {
            MatchedIn.Name -> "name"
            MatchedIn.Category -> "category"
            MatchedIn.Tags -> "tags"
            MatchedIn.Actions -> "actions"
            MatchedIn.Description -> "description"
        }

        fun countOf(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

        /** The friendly name, else the model's suggestion, else the file name. */
        fun tileName(row: MediaRow): String =
            row.friendlyName?.takeIf { it.isNotBlank() }
                ?: row.suggestedName?.takeIf { it.isNotBlank() }
                ?: row.currentPath.substringAfterLast('/')

        /** "0:42", "12:05", "1:02:03". */
        fun durationText(ms: Long): String {
            val total = (ms + 500) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
