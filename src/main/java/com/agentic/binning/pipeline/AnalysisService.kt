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
                    if (intent.getBooleanExtra(EXTRA_CATEGORIZE_ONLY, false)) controller.categorizeNow(ref) else controller.start(ref)
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
                .collect { model ->
                    if (!model.phase.isRunning) {
                        Log.d(TAG, "pipeline ${model.phase}; stopping service")
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                        return@collect
                    }
                    // Paused holds the model but runs nothing: let the CPU sleep until Resume.
                    if (model.phase == PipelinePhase.Paused) releaseWakeLock() else holdWakeLock()
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
        if (model.phase == PipelinePhase.Paused) {
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
        private const val ACTION_START = "com.agentic.binning.action.START_ANALYSIS"
        private const val ACTION_PAUSE = "com.agentic.binning.action.PAUSE_ANALYSIS"
        private const val ACTION_RESUME = "com.agentic.binning.action.RESUME_ANALYSIS"
        private const val ACTION_STOP = "com.agentic.binning.action.STOP_ANALYSIS"
        private const val EXTRA_NAME = "project_name"
        private const val EXTRA_PATH = "project_path"
        private const val EXTRA_CATEGORIZE_ONLY = "categorize_only"

        private fun action(context: Context, action: String) = Intent(context, AnalysisService::class.java).setAction(action)

        /**
         * Analyzes [project]'s Pending items in the foreground service, or with [kind] CategorizeOnly
         * only categorizes its analyzed items. Call while the app is in the foreground.
         */
        fun start(context: Context, project: ProjectRef, kind: RunKind = RunKind.Analyze) {
            context.startForegroundService(
                action(context, ACTION_START)
                    .putExtra(EXTRA_NAME, project.name)
                    .putExtra(EXTRA_PATH, project.path)
                    .putExtra(EXTRA_CATEGORIZE_ONLY, kind == RunKind.CategorizeOnly),
            )
        }
    }
}
