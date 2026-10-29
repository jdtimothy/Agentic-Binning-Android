package com.agentic.binning.model

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.FileProgress
import com.geniex.sdk.bean.ModelPullInput
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach

sealed interface DownloadState {
    data object Absent : DownloadState
    data class Downloading(val fraction: Float) : DownloadState
    data object Ready : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Lets the GenieX SDK download model weights ([ModelManagerWrapper.pullFlow]); pulls resume
 * after a cancel or a crash. Requires `GenieXSdk.getInstance().init(...)` (done in `BinningApp`).
 */
class ModelDownloader(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadState?>>()

    /** Current state of [spec]: Absent or Ready on first look, then whatever a running [pull] reports. */
    fun state(spec: ModelSpec): Flow<DownloadState> = flow {
        val sf = stateFor(spec)
        if (sf.value == null) sf.compareAndSet(null, if (isReady(spec)) DownloadState.Ready else DownloadState.Absent)
        emitAll(sf.filterNotNull())
    }

    /** True once the pull is complete; `getPaths` returns null while files are still in `.inflight/`. */
    suspend fun isReady(spec: ModelSpec): Boolean = ModelManagerWrapper.getPaths(spec.repo) != null

    /**
     * Downloads [spec] unless it is already on the phone, holding a partial wake lock meanwhile.
     * Ends with [DownloadState.Ready] or [DownloadState.Failed].
     */
    fun pull(spec: ModelSpec): Flow<DownloadState> = flow {
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "binning:model_download")
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        try {
            if (isReady(spec)) {
                emit(DownloadState.Ready)
                return@flow
            }
            emit(DownloadState.Downloading(0f))
            var failure: String? = null
            val input = ModelPullInput(
                model_name = spec.repo,
                precision = spec.precision,
                hub = spec.hub,
                chipset = spec.chipset,
            )
            ModelManagerWrapper.pullFlow(input).collect { event ->
                when (event) {
                    is ModelManagerWrapper.PullEvent.Progress -> emit(DownloadState.Downloading(fraction(event.files)))
                    is ModelManagerWrapper.PullEvent.Error -> {
                        Log.e(TAG, "pull ${spec.repo} failed rc=${event.code}: ${event.message}")
                        failure = "Download failed (${event.code}): ${event.message}"
                    }
                    else -> Unit // Completed: confirmed through getPaths below.
                }
            }
            val ready = failure == null && isReady(spec)
            emit(
                when {
                    failure != null -> DownloadState.Failed(failure!!)
                    ready -> DownloadState.Ready
                    else -> DownloadState.Failed("Download ended but the model is not available")
                },
            )
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
        .catch { e ->
            Log.e(TAG, "pull ${spec.repo} threw", e)
            emit(DownloadState.Failed(e.message ?: e.toString()))
        }
        .onEach { stateFor(spec).value = it }

    private fun stateFor(spec: ModelSpec): MutableStateFlow<DownloadState?> =
        states.getOrPut(spec.id) { MutableStateFlow(null) }

    private fun fraction(files: List<FileProgress>): Float {
        val total = files.sumOf { if (it.total_bytes > 0) it.total_bytes else 0L }
        val done = files.sumOf { it.downloaded_bytes }
        return if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    private companion object {
        const val TAG = "ModelDownloader"
        const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
    }
}
