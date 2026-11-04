package com.agentic.binning.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.binning.move.BinPreview
import com.agentic.binning.move.LayoutProblems
import com.agentic.binning.move.MoveOutcome
import com.agentic.binning.move.MoveProgress
import com.agentic.binning.move.MoveReport
import com.agentic.binning.pipeline.MoveStart
import com.agentic.binning.pipeline.PipelinePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bin Files for the open project, as the UI drives it. */
interface BinActions {
    val projectName: String
    val projectPath: String

    /** What a Bin Files would do now; null if it can't tell (the project closed meanwhile). */
    suspend fun preview(): BinPreview?

    fun start(): MoveStart
    fun pause()
    fun resume()
    fun stop()

    /** The newest run's report while the user hasn't closed it (B8). */
    suspend fun unreadReport(): MoveReport?
    suspend fun closeReport(runId: Long)

    /** The date and size the unknown-files sheet shows for a file. */
    suspend fun facts(path: String): UnknownFileFacts

    /** Leave them (B5): the folder check skips these files from now on. */
    suspend fun leave(paths: List<String>)

    /** Add to project (B5): they are analyzed and sorted like any other item. */
    suspend fun add(paths: List<String>)
}

/** The parts of the pipeline's state these surfaces read. */
data class BinPipelineView(
    val phase: PipelinePhase?,
    val progress: MoveProgress?,
    /** Counts finished runs: a change means one just ended. */
    val ended: Int,
    val outcome: MoveOutcome?,
    /** Files that had moved when a resumed run picked up (B3); null for a run started here. */
    val resumedFrom: Int?,
)

sealed interface BinSheetUi {
    data class Confirm(val ui: ConfirmUi) : BinSheetUi
    data class Report(val runId: Long, val ui: ReportUi) : BinSheetUi
    data class Unknown(val ui: UnknownUi) : BinSheetUi
}

data class BinUiState(val card: MoveCardUi?, val sheet: BinSheetUi?) {
    companion object {
        val None = BinUiState(null, null)
    }
}

/**
 * Bin Files from the button to the report (Plan 3 Task 16): the confirm sheet (B7), the progress card
 * with Pause and Stop (B2), the resume banner (B3), the report (B8) and the unknown-files sheet (B5).
 * The moving itself is the pipeline's; this only starts it and shows it.
 */
class BinFilesViewModel(
    view: Flow<BinPipelineView>,
    actions: Flow<BinActions?>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val scope = scope ?: viewModelScope
    private val sheet = MutableStateFlow<BinSheetUi?>(null)
    private val request = MutableStateFlow(MoveRequest.None)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Toast lines: why Bin Files didn't start, and what a sheet's button did. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val viewNow = view.stateIn(this.scope, SharingStarted.Eagerly, BinPipelineView(null, null, 0, null, null))

    /** The last file in progress: between files the engine reports none, and the card never blanks. */
    private var lastFile: MoveProgress? = null

    val state: StateFlow<BinUiState> = combine(viewNow, sheet, request) { v, s, r ->
        BinUiState(card = cardOf(v, r), sheet = s)
    }.stateIn(this.scope, SharingStarted.Eagerly, BinUiState.None)

    @Volatile
    private var current: BinActions? = null

    init {
        this.scope.launch { actions.collect(::onProject) }
        this.scope.launch {
            var ended: Int? = null
            viewNow.collect { v ->
                val isMoving = v.phase?.isMoving == true
                if (!isMoving) {
                    if (request.value != MoveRequest.None) request.value = MoveRequest.None
                } else {
                    // Files are moving: whatever a sheet offered is out of date.
                    if (sheet.value != null) sheet.value = null
                    if (request.value == MoveRequest.Pause && v.progress?.paused == true) request.value = MoveRequest.None
                }
                val last = ended
                ended = v.ended
                if (last != null && last != v.ended) onRunEnded(v.outcome)
            }
        }
    }

    private fun cardOf(v: BinPipelineView, r: MoveRequest): MoveCardUi? {
        val progress = v.progress?.takeIf { v.phase?.isMoving == true }
        if (progress == null) {
            lastFile = null
            return null
        }
        if (progress.current != null) lastFile = progress
        val shown = if (progress.current == null && !progress.paused) {
            lastFile?.let { progress.copy(current = it.current, from = it.from, to = it.to) } ?: progress
        } else {
            progress
        }
        return moveCardUi(shown, v.resumedFrom, r)
    }

    private fun onProject(actions: BinActions?) {
        current = actions
        sheet.value = null
        request.value = MoveRequest.None
        if (actions != null && viewNow.value.phase?.isMoving != true) loadReport(actions)
    }

    private fun onRunEnded(outcome: MoveOutcome?) {
        val a = current ?: return
        when (outcome) {
            is MoveOutcome.Blocked -> showUnknown(a, outcome.problems)
            else -> loadReport(a)
        }
    }

    private fun loadReport(a: BinActions) = launchSafely {
        val report = withContext(io) { a.unreadReport() } ?: return@launchSafely
        if (current === a && viewNow.value.phase?.isMoving != true) sheet.value = BinSheetUi.Report(report.runId, reportUi(report))
    }

    private fun showUnknown(a: BinActions, problems: LayoutProblems) = launchSafely {
        val facts = withContext(io) { problems.unexpected.associateWith { a.facts(it) } }
        if (current === a) sheet.value = BinSheetUi.Unknown(unknownUi(problems, a.projectPath, a.projectName) { facts.getValue(it) })
    }

    /** The dock's Bin Files, when it is on. */
    fun onBinFiles() {
        val a = current ?: return
        launchSafely {
            val preview = withContext(io) { a.preview() } ?: return@launchSafely
            if (current !== a) return@launchSafely
            // B9: a plan with nothing to move only marks and reports; there is nothing to confirm.
            if (preview.files == 0) start(a) else sheet.value = BinSheetUi.Confirm(confirmUi(preview, a.projectName))
        }
    }

    fun onConfirm() {
        sheet.value = null
        current?.let(::start)
    }

    private fun start(a: BinActions) {
        (a.start() as? MoveStart.Refused)?.let { _messages.tryEmit(it.reason) }
    }

    fun onPause() {
        request.value = MoveRequest.Pause
        current?.pause()
    }

    fun onResume() {
        request.value = MoveRequest.None
        current?.resume()
    }

    fun onStop() {
        request.value = MoveRequest.Stop
        current?.stop()
    }

    /** The × , the scrim, Cancel, Close, Done or Back: closing a report means it won't come back (B8). */
    fun onCloseSheet() {
        val open = sheet.value
        sheet.value = null
        if (open is BinSheetUi.Report) closeReport(open.runId)
    }

    private fun closeReport(runId: Long) {
        val a = current ?: return
        launchSafely { withContext(io) { a.closeReport(runId) } }
    }

    /** State 4's Bin N files or Try again carries on with no second confirm; a Done only closes. */
    fun onReportPrimary() {
        val open = sheet.value as? BinSheetUi.Report ?: return
        onCloseSheet()
        if (open.ui.primaryStartsARun) current?.let(::start)
    }

    fun onLeaveFiles() {
        val open = sheet.value as? BinSheetUi.Unknown ?: return
        val a = current ?: return
        sheet.value = null
        launchSafely {
            withContext(io) { a.leave(open.ui.paths) }
            val n = open.ui.paths.size
            _messages.tryEmit(if (n == 1) "1 file left where it is; the check skips it" else "$n files left where they are; the check skips them")
            if (current === a) start(a)
        }
    }

    fun onAddFiles() {
        val open = sheet.value as? BinSheetUi.Unknown ?: return
        val a = current ?: return
        sheet.value = null
        launchSafely {
            withContext(io) { a.add(open.ui.paths) }
            val n = open.ui.paths.size
            _messages.tryEmit(if (n == 1) "1 added · it's analyzed and sorted, then binned" else "$n added · they're analyzed and sorted, then binned")
        }
    }

    /** A project closing under a load, or a full disk, must not take the app down with it. */
    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(BIN_FILES_FAILED)
            }
        }
    }
}

const val BIN_FILES_FAILED = "Bin Files couldn't read this project just now"
