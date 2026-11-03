package com.agentic.binning.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.pipeline.PipelinePhase
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
import com.agentic.binning.projectmedia.activeJobOf
import com.agentic.binning.projectmedia.binFilesBlockedReason
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

    /** The tile's picture: the thumbnail once it is written, else the file. Called off the main thread. */
    fun image(row: MediaRow): File

    /** The detail sheet's item; null once it is gone. */
    suspend fun detail(id: Long): ItemDetail?

    /** The detail sheet's Save, in one transaction. */
    suspend fun save(request: SaveRequest): EditResult

    /** Remove from project: the rows go, the files stay (Q1). */
    suspend fun remove(ids: List<Long>)
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
) {
    companion object {
        val None = MediaUiState(
            hasProject = false, projectName = "", itemCount = 0, query = "", categoryCount = 0, chips = emptyList(),
            kind = null, setFilters = emptyList(), resultText = "", tiles = emptyList(), noMatchText = null,
            status = mediaStatus(MediaCounts(total = 0), null), binReason = null, busyReason = null, selection = null,
            allVisibleSelected = false, empty = false, filtersSheet = null, sheet = null,
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
    val options: List<PickerOption>,
    /** A new category chosen in this draft, listed (selected) above the others. */
    val pendingNew: String?,
    /** The New category… field is open. */
    val newOpen: Boolean,
    val newName: String,
    /** The clash with an existing name (2a's normalizer), shown under the field. */
    val newError: String?,
    val canAdd: Boolean,
)

data class RemoveDialogUi(val title: String, val body: String)

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

/** A tap on an item that is still waiting to be analyzed. */
const val DETAIL_AFTER_ANALYSIS = "Details open once this item is analyzed"

/** Shown when Bin Files is on: binning arrives with Plan 3. */
const val BIN_FILES_LATER = "Bin Files comes in a later version"

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
)

private data class Draft(val shot: String?, val day: LocalDate?)

/** An open detail sheet: the item as loaded, the draft, and what is stacked over it. */
private data class DetailSession(
    val detail: ItemDetail,
    val image: File,
    val imageIsVideo: Boolean,
    val draft: DetailDraft,
    val picker: PickerSession? = null,
    val confirmRemove: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

private data class PickerSession(val newOpen: Boolean = false, val newName: String = "")

/** The rows of one source with what is needed to show them. */
private data class Loaded(
    val source: MediaSource?,
    val all: List<MediaRow>,
    val found: List<MediaRow>,
    val searched: Boolean,
    val categories: List<CategoryEntity>,
    val images: Map<Long, File>,
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
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val searchDebounceMs: Long = 150,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val picks = MutableStateFlow(Picks())
    private val session = MutableStateFlow<DetailSession?>(null)

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
            combine(all, found, src.categories()) { a, (f, searched), c ->
                Loaded(src, a, f, searched, c, f.associate { it.id to src.image(it) })
            }.flowOn(io)
        }
    }

    val state: StateFlow<MediaUiState> = combine(loaded, picks, phase.distinctUntilChanged(), session) { l, p, ph, d -> build(l, p, ph, d) }
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
     * In select mode a tap ticks or unticks. Otherwise it opens the item's detail sheet; an item not
     * analyzed yet says so, and failed or missing items get their own sheet in Task 16.
     */
    fun onTileClick(id: Long) {
        val p = picks.value
        if (p.selection != null) {
            picks.update { it.copy(selection = it.selection?.toggle(id)) }
            return
        }
        val tile = state.value.tiles.firstOrNull { it.id == id } ?: return
        when {
            tile.badge == TileBadge.Failed -> Unit
            !opensDetail(tile.status) -> if (tile.status == ItemStatus.Pending || tile.status == ItemStatus.Analyzing) {
                _messages.tryEmit(DETAIL_AFTER_ANALYSIS)
            }
            else -> openDetail(tile)
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

    fun onPickCategory(id: Long) {
        val name = state.value.sheet?.picker?.options?.firstOrNull { it.id == id }?.name ?: return
        updateSession { it.copy(draft = it.draft.copy(pick = CategoryPick.Existing(id, name)), picker = null, error = null) }
    }

    /** Keeps the new category already chosen in this draft. */
    fun onPickPendingNew() = onClosePicker()

    fun onPickerNewOpen() = updateSession { s -> s.copy(picker = (s.picker ?: PickerSession()).copy(newOpen = true)) }

    fun onPickerNewName(text: String) = updateSession { s -> s.copy(picker = s.picker?.copy(newName = text)) }

    fun onPickerAddNew() {
        val picker = state.value.sheet?.picker ?: return
        if (!picker.canAdd) return
        val name = CategoryNames.clean(picker.newName)
        updateSession { it.copy(draft = it.draft.copy(pick = CategoryPick.New(name)), picker = null, error = null) }
    }

    fun onSaveDetail() {
        val s = session.value ?: return
        val src = current ?: return
        if (s.saving || !detailUi(s.detail, s.draft, zone).saveEnabled) return
        val request = saveRequest(s.detail, s.draft)
        session.value = s.copy(saving = true, error = null)
        scope.launch {
            val result = withContext(io) { src.save(request) }
            if (current !== src) return@launch
            when (result) {
                EditResult.Ok, is EditResult.Created -> session.value = null
                is EditResult.Clash -> updateSession { it.copy(saving = false, error = "A category named “${result.existing}” already exists.") }
                EditResult.InvalidName -> updateSession { it.copy(saving = false, error = "That category name can't be used.") }
                EditResult.NotFound -> {
                    session.value = null
                    _messages.tryEmit("This item is no longer in the project")
                }
            }
        }
    }

    fun onRemoveDetail() = updateSession { it.copy(confirmRemove = true) }

    fun onCancelRemove() = updateSession { it.copy(confirmRemove = false) }

    fun onConfirmRemove() {
        val s = session.value ?: return
        val src = current ?: return
        session.value = null
        scope.launch { withContext(io) { src.remove(listOf(s.detail.item.id)) } }
    }

    fun onTileLongPress(id: Long) = picks.update { p -> p.copy(selection = (p.selection ?: emptySet()).let { if (id in it) it else it + id }) }

    fun onStartSelect() = picks.update { it.copy(selection = it.selection ?: emptySet()) }

    fun onExitSelect() = picks.update { it.copy(selection = null) }

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

    /** Disabled with the reason while a run is in progress; the sheet comes with Task 15. */
    fun onResort() {
        state.value.busyReason?.let { _messages.tryEmit(it) }
    }

    fun onBinFiles() {
        val s = state.value
        _messages.tryEmit(s.binReason ?: BIN_FILES_LATER)
    }

    private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

    private fun build(l: Loaded, p: Picks, phase: PipelinePhase?, d: DetailSession?): MediaUiState {
        val src = l.source ?: return MediaUiState.None
        val job = activeJobOf(phase)
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
            status = mediaStatus(counts, job),
            binReason = binFilesBlockedReason(counts, job),
            busyReason = if (job != null) RESORT_BUSY else null,
            selection = selection,
            allVisibleSelected = selection != null && tiles.isNotEmpty() && tiles.all { it.id in selection },
            empty = empty,
            filtersSheet = sheet,
            sheet = d?.let { detailSheet(it, l, src.projectName) },
        )
    }

    private fun detailSheet(d: DetailSession, l: Loaded, projectName: String): DetailSheetState {
        val item = d.detail.item
        val picker = d.picker?.let { ps ->
            val chosen = when (val pick = d.draft.pick) {
                is CategoryPick.Existing -> pick.id
                is CategoryPick.New -> null
                null -> item.categoryId
            }
            val byCategory = l.all.groupingBy { it.categoryId }.eachCount()
            val clean = CategoryNames.clean(ps.newName)
            val clash = if (clean.isEmpty()) null else CategoryNames.clash(clean, l.categories.map { it.name })
            PickerUi(
                options = l.categories.map { c ->
                    PickerOption(c.id, c.name, c.description, byCategory[c.id] ?: 0, locked = c.createdBy == "user", selected = c.id == chosen)
                },
                pendingNew = (d.draft.pick as? CategoryPick.New)?.name,
                newOpen = ps.newOpen,
                newName = ps.newName,
                newError = clash?.let { "That is the same as “$it”. Pick it from the list instead." },
                canAdd = clean.isNotEmpty() && clash == null,
            )
        }
        val name = item.friendlyName?.takeIf { it.isNotBlank() } ?: item.originalName
        return DetailSheetState(
            ui = detailUi(d.detail, d.draft, zone),
            image = d.image,
            imageIsVideo = d.imageIsVideo,
            picker = picker,
            remove = if (d.confirmRemove) {
                RemoveDialogUi(
                    title = "Remove “$name” from $projectName?",
                    body = "The file stays on your phone where it is now. The project forgets its analysis, name and category. " +
                        "You can add it again later.",
                )
            } else {
                null
            },
            error = d.error,
            saving = d.saving,
        )
    }

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
