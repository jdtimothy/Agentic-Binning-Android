package com.agentic.binning.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.agentic.binning.BinningApp
import com.agentic.binning.MainActivity
import com.agentic.binning.R
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.projectmedia.ResortRequest
import com.agentic.binning.projectmedia.ResortStart
import com.agentic.binning.settings.SortingModelChoice
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps analysis and categorizing alive with the screen off or the app in the background: a foreground service with
 * a progress notification (Pause/Resume and Stop actions) and a partial wake lock, held while the
 * [PipelineController] is working (not while Paused). It stops itself once the controller is Idle or Stopped.
 */
class AnalysisService : LifecycleService() {
    private val controller: PipelineController get() = (application as BinningApp).graph.pipeline
    private var observer: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                val name = intent.getStringExtra(EXTRA_NAME)
                val path = intent.getStringExtra(EXTRA_PATH)
                if (name != null && path != null) {
                    val ref = ProjectRef(name, path)
                    when (RunKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: RunKind.Analyze) {
                        RunKind.Analyze -> controller.start(ref)
                        RunKind.CategorizeOnly -> controller.categorizeNow(ref)
                        RunKind.Resort -> resort(ref, intent)
                        RunKind.BinFiles -> logRefusal(controller.binFiles(ref))
                        RunKind.SaveMoves -> logRefusal(controller.saveMoves(ref, intent.getLongExtra(EXTRA_MOVE_RUN, 0L)))
                        RunKind.ResumeMove -> logRefusal(
                            controller.resumeMove(ref, intent.getLongExtra(EXTRA_MOVE_RUN, 0L), intent.getBooleanExtra(EXTRA_MOVE_PAUSED, false)),
                        )
                    }
                }
                stopIfNotRunning(startId)
            }
            ACTION_RESUME -> {
                goForeground()
                controller.resume()
                stopIfNotRunning(startId)
            }
            ACTION_PAUSE -> controller.pause()
            ACTION_STOP -> (application as BinningApp).graph.stopAnalysis()
        }
        observe()
        return START_NOT_STICKY
    }

    private fun logRefusal(start: MoveStart) {
        if (start is MoveStart.Refused) Log.w(TAG, "Move refused: ${start.reason}")
    }

    /** Starts the Re-sort the intent describes; a refusal (a run began meanwhile) is only logged. */
    private fun resort(ref: ProjectRef, intent: Intent) {
        val model = SortingModelChoice.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_RESORT_MODEL) }
        if (model == null) {
            Log.w(TAG, "Re-sort start without a model; ignored")
            return
        }
        val start = controller.resort(ref, ResortRequest(model, intent.getBooleanExtra(EXTRA_KEEP_EDITS, true)))
        if (start is ResortStart.Refused) Log.w(TAG, "Re-sort refused: ${start.reason}")
    }

    /**
     * A start the controller refused (another project is open, or a switch is in progress) or a
     * resume with nothing to resume leaves the state unchanged, so the observer sees no new value
     * and would leave the notification up: take it down here.
     */
    private fun stopIfNotRunning(startId: Int) {
        if (controller.state.value.phase.isRunning) return
        Log.d(TAG, "pipeline not running after start; stopping service")
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun goForeground() {
        startForeground(NOTIFICATION_ID, buildNotification(controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun observe() {
        if (observer != null) return
        observer = lifecycleScope.launch {
            // Notifications are rate-limited, so update only when what they show changes (not per token).
            controller.state
                .map { NotificationModel.of(it) }
                .distinctUntilChanged()
                .collectPaced(NOTIFICATION_GAP_MS) { model ->
                    if (!model.phase.isRunning) {
                        Log.d(TAG, "pipeline ${model.phase}; stopping service")
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                        return@collectPaced
                    }
                    // Paused holds the model (or a move between files) but runs nothing: let the CPU sleep until Resume.
                    if (model.phase == PipelinePhase.Paused || model.phase == PipelinePhase.MovingPaused) releaseWakeLock() else holdWakeLock()
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(controller.state.value))
                }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        // Without the foreground service the process may be frozen; leave items Pending instead.
        if (controller.state.value.phase.isRunning) controller.stop()
        super.onDestroy()
    }

    /** Re-acquiring a non-counted lock pushes its timeout back, so it lapses only if progress stalls. */
    private fun holdWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "binning:analysis")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun notificationManager(): NotificationManager {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Analysis", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while photos and clips are analyzed"
                },
            )
        }
        return manager
    }

    private fun buildNotification(state: PipelineState): Notification {
        notificationManager()
        val model = NotificationModel.of(state)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_analysis)
            .setContentTitle(model.title)
            .setContentText(model.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        when {
            model.progressMax > 0 -> builder.setProgress(model.progressMax, model.progress, false)
            model.phase.isRunning -> builder.setProgress(0, 0, true)
        }
        if (model.phase == PipelinePhase.Paused || model.phase == PipelinePhase.MovingPaused) {
            builder.addAction(0, "Resume", PendingIntent.getForegroundService(this, 2, action(this, ACTION_RESUME), PendingIntent.FLAG_IMMUTABLE))
        } else if (model.phase.canPause) {
            // Not while categorizing: there is nothing to pause between chunks.
            builder.addAction(0, "Pause", PendingIntent.getService(this, 1, action(this, ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE))
        }
        builder.addAction(0, "Stop", PendingIntent.getService(this, 3, action(this, ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        return builder.build()
    }

    /** What the notification shows; equal models need no update. */
    private data class NotificationModel(
        val phase: PipelinePhase,
        val title: String,
        val text: String,
        val progress: Int,
        val progressMax: Int,
    ) {
        companion object {
            /** Bin Files (B2, Q11): "Binning Trip · 13 of 248", the file in progress, Pause/Resume and Stop. */
            fun moving(s: PipelineState, name: String): NotificationModel {
                val p = s.moveProgress
                val done = p?.done ?: 0
                val total = p?.total ?: 0
                val save = p?.kind == com.agentic.binning.move.MoveKind.Save
                val paused = s.phase == PipelinePhase.MovingPaused
                val count = if (total > 0) " · ${minOf(done + if (paused) 0 else 1, total)} of $total" else ""
                val title = when {
                    paused && save -> "Saving paused$count"
                    paused -> "Bin Files paused · $name$count"
                    save -> "Saving$count"
                    else -> "Binning $name$count"
                }
                val text = when {
                    paused -> "The file in progress finished"
                    p?.current == null -> "Checking the folders"
                    else -> "${p.phase?.label ?: "Moving"} ${p.current}"
                }
                return NotificationModel(s.phase, title, text, done, total)
            }

            private val com.agentic.binning.move.MovePhase.label: String
                get() = when (this) {
                    com.agentic.binning.move.MovePhase.Moving -> "Moving"
                    com.agentic.binning.move.MovePhase.Copying -> "Copying"
                    com.agentic.binning.move.MovePhase.Checking -> "Checking"
                    com.agentic.binning.move.MovePhase.Placing -> "Placing"
                    com.agentic.binning.move.MovePhase.DeletingOriginal -> "Deleting the original of"
                }

            fun of(s: PipelineState): NotificationModel {
                val name = s.project?.name ?: "project"
                val download = s.downloadFraction
                // Failed and Missing items are done too, so the bar can fill.
                val done = s.analyzed + s.failed
                val tally = "${s.analyzed} of ${s.total} analyzed" + if (s.failed > 0) " · ${s.failed} failed" else ""
                return when {
                    download != null -> NotificationModel(
                        s.phase,
                        "Downloading the vision model",
                        "${(download * 100).roundToInt()}% · then analyzing $name",
                        (download * 100).roundToInt(),
                        100,
                    )
                    s.phase.isMoving -> moving(s, name)
                    s.phase == PipelinePhase.Paused -> NotificationModel(
                        s.phase, "Paused · $name · ${s.pending} left", tally, done, s.total,
                    )
                    s.phase == PipelinePhase.Categorizing -> NotificationModel(
                        s.phase,
                        "Categorizing $name",
                        "Categorizing ${s.sortDone} of ${s.sortTotal}",
                        s.sortDone,
                        s.sortTotal,
                    )
                    else -> NotificationModel(
                        s.phase,
                        "Analyzing $name · ${s.pending} left",
                        when (s.phase) {
                            PipelinePhase.LoadingModel -> "Loading the vision model"
                            PipelinePhase.Releasing -> "Finishing up"
                            else -> tally
                        },
                        done,
                        s.total,
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "AnalysisService"
        private const val CHANNEL_ID = "analysis"
        private const val NOTIFICATION_ID = 11
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        private const val NOTIFICATION_GAP_MS = 500L
        private const val ACTION_START = "com.agentic.binning.action.START_ANALYSIS"
        private const val ACTION_PAUSE = "com.agentic.binning.action.PAUSE_ANALYSIS"
        private const val ACTION_RESUME = "com.agentic.binning.action.RESUME_ANALYSIS"
        private const val ACTION_STOP = "com.agentic.binning.action.STOP_ANALYSIS"
        private const val EXTRA_NAME = "project_name"
        private const val EXTRA_PATH = "project_path"
        private const val EXTRA_KIND = "run_kind"
        private const val EXTRA_RESORT_MODEL = "resort_model"
        private const val EXTRA_KEEP_EDITS = "resort_keep_edits"
        private const val EXTRA_MOVE_RUN = "move_run_id"
        private const val EXTRA_MOVE_PAUSED = "move_paused"

        private fun action(context: Context, action: String) = Intent(context, AnalysisService::class.java).setAction(action)

        /**
         * Analyzes [project]'s Pending items in the foreground service, or with [kind] CategorizeOnly
         * only categorizes its analyzed items, or with [kind] Resort re-sorts it as [resort] says (required
         * then). Call while the app is in the foreground.
         */
        fun start(
            context: Context,
            project: ProjectRef,
            kind: RunKind = RunKind.Analyze,
            resort: ResortRequest? = null,
            moveRunId: Long = 0L,
            movePaused: Boolean = false,
        ) {
            require((kind == RunKind.Resort) == (resort != null)) { "A Re-sort start needs its request, and only it" }
            val intent = action(context, ACTION_START)
                .putExtra(EXTRA_NAME, project.name)
                .putExtra(EXTRA_PATH, project.path)
                .putExtra(EXTRA_KIND, kind.name)
            resort?.let { intent.putExtra(EXTRA_RESORT_MODEL, it.model.name).putExtra(EXTRA_KEEP_EDITS, it.keepEdits) }
            if (kind == RunKind.ResumeMove || kind == RunKind.SaveMoves) intent.putExtra(EXTRA_MOVE_RUN, moveRunId).putExtra(EXTRA_MOVE_PAUSED, movePaused)
            context.startForegroundService(intent)
        }
    }
}
