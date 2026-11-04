package com.agentic.binning.move

import com.agentic.binning.db.MoveStepEntity
import java.io.IOException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Tells MediaStore about a moved file (B11); [from] is null when the source stays (a copy, P8). */
fun interface MoveNotifier {
    fun moved(from: String?, to: String)
}

/**
 * Pause and Stop for a running move (B2). The engine reads it only between files, so the file in
 * progress always finishes first.
 */
class MoveControl(startPaused: Boolean = false) {
    enum class Request { Go, Pause, Stop }

    private val _request = MutableStateFlow(if (startPaused) Request.Pause else Request.Go)
    val request: StateFlow<Request> = _request.asStateFlow()

    fun pause() {
        _request.compareAndSet(Request.Go, Request.Pause)
    }

    fun resume() {
        _request.compareAndSet(Request.Pause, Request.Go)
    }

    fun stop() {
        _request.value = Request.Stop
    }
}

/**
 * Runs a written plan (`move_step` rows) one file at a time, never losing or overwriting a file.
 *
 * - Same volume (B1): rename, then check the file is at its target with the same size and gone from
 *   the source. A failed check stops the run (it can't be skipped); an I/O error that leaves the
 *   source untouched skips the file.
 * - Another volume, or a source another project holds (P8): copy to `.binning/moving/<step>.tmp`
 *   hashing the source, read the copy back (size and SHA-256), rename it into place, record it, then
 *   delete the original (never for a shared source). A bad copy is retried once, then skipped (B4).
 * - A step found part-way after a crash (B3) is finished from its recorded state; a step started a
 *   third time without finishing is skipped, unless its file is already recorded at the destination.
 * - Pause and Stop ([MoveControl]) and cancellation act only between files; each step runs under
 *   [NonCancellable]. A cancelled run stays open in the database, so it resumes when the project opens.
 *
 * The caller (BinFilesJob) writes the plan, removes empty folders and finishes the run.
 */
class MoveEngine(
    private val store: MoveStore,
    private val files: MoveFiles,
    private val notifier: MoveNotifier,
    projectDir: String,
) {
    private val root = projectDir.trimEnd('/')

    private sealed interface StepResult {
        data object Next : StepResult
        data class Moved(val dest: String) : StepResult
        data class Stop(val outcome: MoveOutcome) : StepResult
    }

    suspend fun run(runId: Long, kind: MoveKind, control: MoveControl, onProgress: (MoveProgress) -> Unit): MoveOutcome {
        val steps = store.steps(runId)
        val total = steps.size
        var done = steps.count { StepState.of(it.state).isFinal }
        val recent = ArrayDeque(steps.filter { StepState.of(it.state) == StepState.Done }.takeLast(RECENT).map { movedFile(it.dest) })
        var active: MoveStepEntity? = null
        fun report(current: String?, phase: MovePhase?, paused: Boolean = false, next: String? = null) = onProgress(
            MoveProgress(
                kind, done, total, current, phase, paused,
                from = active?.takeIf { current != null }?.let { folder(it.source) },
                to = active?.takeIf { current != null }?.let { folder(it.dest) },
                next = next,
                recent = recent.toList(),
            ),
        )

        for (step in steps) {
            if (StepState.of(step.state).isFinal) continue
            // Between files: Stop, Pause, cancellation and the space floor.
            if (control.request.value == MoveControl.Request.Pause) {
                store.setRunState(runId, RunState.Paused.name, null)
                report(null, null, paused = true, next = name(step.dest))
                val next = control.request.first { it != MoveControl.Request.Pause }
                if (next == MoveControl.Request.Stop) return MoveOutcome.Stopped
                store.setRunState(runId, RunState.Running.name, null)
            }
            if (control.request.value == MoveControl.Request.Stop) return MoveOutcome.Stopped
            currentCoroutineContext().ensureActive()
            val free = files.usableSpace(root)
            if (free < MIN_FREE_BYTES) return MoveOutcome.StorageFull(MIN_FREE_BYTES - free)

            active = step
            val result = withContext(NonCancellable) {
                runStep(step) { current, phase -> report(current, phase) }
            }
            when (result) {
                StepResult.Next -> {
                    done++
                    report(null, null)
                }
                is StepResult.Moved -> {
                    done++
                    recent.addLast(movedFile(result.dest))
                    if (recent.size > RECENT) recent.removeFirst()
                    report(null, null)
                }
                is StepResult.Stop -> return result.outcome
            }
        }
        return MoveOutcome.Done
    }

    private suspend fun runStep(planned: MoveStepEntity, report: (String, MovePhase) -> Unit): StepResult {
        var step = planned
        val starts = store.startStep(step.id)
        val state = StepState.of(step.state)
        val resumed = starts > 1
        val temp = tempFor(step)

        if (state == StepState.Verified) return finishVerified(step, report)

        // Planned or Copied: an unchecked temp copy is never trusted.
        if (files.exists(temp)) files.delete(temp)
        if (state == StepState.Copied) store.setStepState(step.id, StepState.Planned.name, null)
        if (starts > MAX_STARTS) {
            store.setStepState(step.id, StepState.Skipped.name, StepErrors.INTERRUPTED)
            return StepResult.Next
        }

        if (!files.exists(step.source)) {
            // A rename that happened just before the app died, not yet recorded.
            if (resumed && files.exists(step.dest) && files.size(step.dest) == step.bytes) {
                store.recordMoved(step.id, step.itemId, step.dest, files.sha256(step.dest), StepState.Done.name)
                notifier.moved(step.source, step.dest)
                return StepResult.Moved(step.dest)
            }
            store.recordMissing(step.id, step.itemId)
            return StepResult.Next
        }

        if (files.exists(step.dest)) step = withDest(step, MovePlanner.nextFree(step.dest, files::exists))
        val destDir = step.dest.substringBeforeLast('/')
        return if (!step.keepSource && files.sameVolume(step.source, destDir)) {
            rename(step, report)
        } else {
            copy(step, temp, report)
        }
    }

    private suspend fun rename(start: MoveStepEntity, report: (String, MovePhase) -> Unit): StepResult {
        var step = start
        val size = files.size(step.source)
        files.mkdirs(step.dest.substringBeforeLast('/'))
        report(name(step.dest), MovePhase.Moving)
        try {
            var tries = 0
            while (!files.renameNoReplace(step.source, step.dest)) {
                // Taken since the check a moment ago: take the next number, never overwrite.
                if (++tries >= RENAME_TRIES) throw IOException("No free name for ${step.dest}")
                step = withDest(step, MovePlanner.nextFree(step.dest, files::exists))
            }
        } catch (e: IOException) {
            if (files.exists(step.source) && !files.exists(step.dest)) {
                store.setStepState(step.id, StepState.Skipped.name, StepErrors.IO)
                return StepResult.Next
            }
            store.setStepState(step.id, StepState.Failed.name, StepErrors.RENAME_CHECK)
            return StepResult.Stop(MoveOutcome.Failed("Moving ${name(step.source)} went wrong: ${e.message}"))
        }
        report(name(step.dest), MovePhase.Checking)
        val ok = files.exists(step.dest) && files.size(step.dest) == size && !files.exists(step.source)
        if (!ok) {
            store.setStepState(step.id, StepState.Failed.name, StepErrors.RENAME_CHECK)
            return StepResult.Stop(MoveOutcome.Failed("${name(step.source)} wasn't where it should be after moving it"))
        }
        store.recordMoved(step.id, step.itemId, step.dest, files.sha256(step.dest), StepState.Done.name)
        notifier.moved(step.source, step.dest)
        return StepResult.Moved(step.dest)
    }

    private suspend fun copy(start: MoveStepEntity, temp: String, report: (String, MovePhase) -> Unit): StepResult {
        var step = start
        val size = files.size(step.source)
        val free = files.usableSpace(root)
        if (free < size + COPY_MARGIN_BYTES) return StepResult.Stop(MoveOutcome.StorageFull(size + COPY_MARGIN_BYTES - free))
        files.mkdirs(temp.substringBeforeLast('/'))

        var hash: String? = null
        for (attempt in 1..COPY_TRIES) {
            report(name(step.dest), MovePhase.Copying)
            val sourceHash = try {
                files.copyHashing(step.source, temp)
            } catch (e: NoSpaceException) {
                files.delete(temp)
                store.setStepState(step.id, StepState.Planned.name, null)
                return StepResult.Stop(MoveOutcome.StorageFull(size))
            } catch (e: IOException) {
                files.delete(temp)
                store.setStepState(step.id, StepState.Skipped.name, StepErrors.IO)
                return StepResult.Next
            }
            files.setLastModified(temp, files.lastModified(step.source))
            store.setStepState(step.id, StepState.Copied.name, null)
            report(name(step.dest), MovePhase.Checking)
            if (files.size(temp) == size && files.sha256(temp) == sourceHash) {
                hash = sourceHash
                break
            }
            files.delete(temp)
            store.setStepState(step.id, StepState.Planned.name, null)
        }
        if (hash == null) {
            store.setStepState(step.id, StepState.Skipped.name, StepErrors.COPY_MISMATCH)
            return StepResult.Next
        }

        report(name(step.dest), MovePhase.Placing)
        files.mkdirs(step.dest.substringBeforeLast('/'))
        var tries = 0
        while (!files.renameNoReplace(temp, step.dest)) {
            if (++tries >= RENAME_TRIES) {
                files.delete(temp)
                store.setStepState(step.id, StepState.Skipped.name, StepErrors.IO)
                return StepResult.Next
            }
            step = withDest(step, MovePlanner.nextFree(step.dest, files::exists))
        }
        store.recordMoved(step.id, step.itemId, step.dest, hash, StepState.Verified.name)
        return finishVerified(step, report)
    }

    /** The file is at its destination and recorded: delete the original (unless shared) and finish. */
    private suspend fun finishVerified(step: MoveStepEntity, report: (String, MovePhase) -> Unit): StepResult {
        if (!files.exists(step.dest)) {
            // Gone since it was recorded; never delete the only copy left.
            if (files.exists(step.source)) {
                store.recordReverted(step.id, step.itemId, step.source, StepErrors.IO)
            } else {
                store.recordMissing(step.id, step.itemId)
            }
            return StepResult.Next
        }
        if (!step.keepSource && files.exists(step.source)) {
            report(name(step.dest), MovePhase.DeletingOriginal)
            files.delete(step.source)
        }
        store.setStepState(step.id, StepState.Done.name, null)
        notifier.moved(if (step.keepSource) null else step.source, step.dest)
        return StepResult.Moved(step.dest)
    }

    private suspend fun withDest(step: MoveStepEntity, dest: String): MoveStepEntity {
        store.setStepDest(step.id, dest)
        return step.copy(dest = dest)
    }

    private fun tempFor(step: MoveStepEntity) = "$root/.binning/moving/${step.id}.tmp"

    private fun name(path: String) = path.substringAfterLast('/')

    private fun folder(path: String) = name(path.substringBeforeLast('/'))

    private fun movedFile(dest: String) = MovedFile(name(dest), folder(dest))

    companion object {
        /** Every run keeps this much free, so the database journal always has room (B6). */
        const val MIN_FREE_BYTES = 50L * 1024 * 1024

        /** A copy needs its own size plus this much free (B6). */
        const val COPY_MARGIN_BYTES = 200L * 1024 * 1024

        /** A step may start twice (once, and once more after a crash); the third start skips it (B3). */
        const val MAX_STARTS = 2

        /** Copies of one file before it is skipped (B4). */
        const val COPY_TRIES = 2

        private const val RENAME_TRIES = 5

        /** Finished files the progress card lists. */
        private const val RECENT = 2
    }
}
