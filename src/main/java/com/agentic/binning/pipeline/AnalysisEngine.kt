package com.agentic.binning.pipeline

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.AnalysisParser
import com.agentic.binning.analysis.AnalysisResult
import com.agentic.binning.analysis.MomentPrompt
import com.agentic.binning.analysis.VideoMoments
import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.model.VisionProfile
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Turns an item into the images the model reads ([com.agentic.binning.analysis.MediaPreparer] in the app). Blocking. */
interface Preparer {
    /**
     * @throws FileNotFoundException if the item's file is gone.
     * @throws IOException (any other) if the app's own work files or thumbnails cannot be written,
     * e.g. on a full disk: that is not the item's fault, so it stops the run.
     * @throws Exception (typically `MediaDecodeException`) if the item's file cannot be decoded.
     * A video yields at most [maxFrames] frames. [onFrame] reports each as it is extracted (frames ready,
     * frames to extract).
     */
    fun prepare(item: ItemEntity, maxFrames: Int, onFrame: (ready: Int, total: Int) -> Unit = { _, _ -> }): PreparedMedia

    /** Deletes the work files [prepare] wrote for [itemId]; never touches the item's own file. */
    fun cleanup(itemId: Long)
}

/** Item counts for progress: [pending] includes the item being analyzed; [analyzed] counts every item past analysis. */
data class ItemCounts(val pending: Int, val analyzed: Int, val total: Int, val failed: Int = 0)

/** The pipeline's view of the current project's database. */
interface AnalysisStore {
    suspend fun nextPending(): ItemEntity?
    suspend fun setStatus(id: Long, status: ItemStatus, reason: String? = null)

    /** Marks the item Analyzing and counts the attempt (see [recoverInterrupted]). */
    suspend fun markAnalyzing(id: Long)

    /** Puts an item whose analysis was stopped cleanly back to Pending and clears its attempt count. */
    suspend fun returnToPending(id: Long)

    /**
     * For items left Analyzing by a process that died: those with at least [failAfter] attempts
     * become Failed ([FailureReasons.INTERRUPTED]), so an item that crashes the app natively is not
     * retried on every launch; the rest go back to Pending. Returns how many items it touched.
     */
    suspend fun recoverInterrupted(failAfter: Int): Int

    /** Stores the analysis and marks the item Analyzed. */
    suspend fun save(itemId: Long, result: AnalysisResult, raw: String, modelId: String, profile: VisionProfile)
    suspend fun counts(): ItemCounts
}

/**
 * Ends the whole run in Stopped with [message] (written for people) as its error; the current item
 * goes back to Pending. For failures that are not the item's fault, so every item would fail alike.
 */
class StopRunException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface ItemOutcome {
    data class Analyzed(val result: AnalysisResult) : ItemOutcome
    data class Failed(val reason: String) : ItemOutcome
    object Missing : ItemOutcome
    object Cancelled : ItemOutcome
}

/**
 * Analyzes one item: prepare, generate (retrying once on a `length` stop or unparseable JSON), parse
 * and save. Every outcome except [ItemOutcome.Cancelled] is written to [store]; Cancelled leaves the
 * status for the caller. Failures of the phone rather than the item (the app's storage, the model or
 * the NPU) throw [StopRunException] and leave the status for the caller too. The preparer's work
 * files are always cleaned up.
 */
class AnalysisEngine(
    private val model: VisionModel,
    private val preparer: Preparer,
    private val store: AnalysisStore,
    /** The most frames a video is sampled to; fixed for the engine's life (the controller makes one per batch). */
    private val maxFrames: Int,
    /** Where the blocking [Preparer.prepare] runs; the app passes `Dispatchers.IO`. */
    private val prepareContext: CoroutineContext = EmptyCoroutineContext,
    private val log: PipelineLog = PipelineLog.None,
) {
    /** Profile of the most recent reply, including failed attempts. */
    @Volatile
    var lastProfile: VisionProfile? = null
        private set

    suspend fun analyze(item: ItemEntity, onPartial: (PartialAnalysis) -> Unit): ItemOutcome =
        analyze(item, onDescribing = {}, onPartial = onPartial)

    /**
     * [onDescribing] is called once the media is prepared, just before the model starts; [onFrame]
     * as each of a video's frames is extracted.
     */
    suspend fun analyze(
        item: ItemEntity,
        onDescribing: () -> Unit,
        onFrame: (ready: Int, total: Int) -> Unit = { _, _ -> },
        onPartial: (PartialAnalysis) -> Unit,
    ): ItemOutcome {
        val started = System.nanoTime()
        try {
            val media = try {
                withContext(prepareContext) { preparer.prepare(item, maxFrames, onFrame) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: FileNotFoundException) {
                store.setStatus(item.id, ItemStatus.Missing)
                return ItemOutcome.Missing
            } catch (e: IOException) {
                log.warn("Cannot write the work files of item ${item.id}", e)
                throw StopRunException(FailureReasons.OUT_OF_SPACE, e)
            } catch (e: Exception) {
                log.warn("Cannot prepare item ${item.id} (${item.originalName})", e)
                return fail(item, REASON_DECODE)
            }
            onDescribing()

            var reason = REASON_INVALID_JSON
            repeat(MAX_ATTEMPTS) {
                val text = StringBuilder()
                val reply = try {
                    model.generate(media) { token ->
                        text.append(token)
                        onPartial(AnalysisParser.partial(text.toString()))
                    }
                } catch (e: CancellationException) {
                    throw e // A CancellationException is an IllegalStateException: keep this first.
                } catch (e: IllegalStateException) {
                    log.warn("Prompt assembly failed for item ${item.id}", e) // Or the media-marker check.
                    return fail(item, REASON_PROMPT)
                } catch (e: VisionModelException) {
                    log.warn("The vision model failed on item ${item.id}", e)
                    throw StopRunException(FailureReasons.MODEL_FAILED, e)
                }
                lastProfile = reply.profile
                when (reply.profile.stopReason) {
                    STOP_USER -> return ItemOutcome.Cancelled
                    STOP_LENGTH -> reason = REASON_LENGTH
                    else -> {
                        val result = AnalysisParser.parse(reply.text, item.kind)
                        if (result != null) {
                            val finalResult = if (item.kind == ItemKind.Video) {
                                val actions = try {
                                    momentActions(item.id, media, result, onPartial) ?: return ItemOutcome.Cancelled
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: IllegalStateException) {
                                    log.warn("Moment prompt assembly failed for item ${item.id}", e)
                                    return fail(item, REASON_PROMPT)
                                } catch (e: VisionModelException) {
                                    log.warn("The vision model failed on a moment of item ${item.id}", e)
                                    throw StopRunException(FailureReasons.MODEL_FAILED, e)
                                }
                                result.copy(actions = actions)
                            } else result
                            val raw = if (item.kind == ItemKind.Video) AnalysisParser.withActions(reply.text, finalResult.actions) else reply.text
                            store.save(item.id, finalResult, raw, model.modelId, reply.profile)
                            return ItemOutcome.Analyzed(finalResult)
                        }
                        reason = REASON_INVALID_JSON
                    }
                }
            }
            return fail(item, reason)
        } finally {
            preparer.cleanup(item.id)
            log.warn("Analysis item ${item.id} total ${(System.nanoTime() - started) / 1_000_000} ms", null)
        }
    }

    /** Null signals a user-stopped native call; coroutine cancellation still propagates. */
    private suspend fun momentActions(
        itemId: Long,
        media: PreparedMedia,
        result: AnalysisResult,
        onPartial: (PartialAnalysis) -> Unit,
    ): List<ActionEntry>? {
        val actions = mutableListOf<ActionEntry>()
        val aligned = media.frameChanges.size == media.imagePaths.size && media.frameTimesMs.size == media.imagePaths.size
        val picked = if (aligned) VideoMoments.select(media.frameChanges) else emptyList()
        log.warn("Moments item $itemId: frames $picked times ${media.frameTimesMs} changes ${media.frameChanges}", null)
        for (frame in picked) {
            var attempt = 0
            while (attempt++ < MAX_ATTEMPTS) {
                currentCoroutineContext().ensureActive()
                val reply = model.describeMoment(media.imagePaths[frame])
                lastProfile = reply.profile
                log.warn("Moment item $itemId frame $frame time ${media.frameTimesMs[frame]} ms: ${reply.profile} ${reply.text}", null)
                if (reply.profile.stopReason == STOP_USER) return null
                if (reply.profile.stopReason == STOP_LENGTH) continue
                MomentPrompt.clean(reply.text)?.let { phrase ->
                    actions += ActionEntry(VideoMoments.timestamp(media.frameTimesMs[frame]), phrase)
                    onPartial(PartialAnalysis(result.description, true, actions.toList(), result.tags))
                }
                break
            }
        }
        val finalActions = VideoMoments.distinct(actions)
        onPartial(PartialAnalysis(result.description, true, finalActions, result.tags))
        return finalActions
    }

    private suspend fun fail(item: ItemEntity, reason: String): ItemOutcome.Failed {
        store.setStatus(item.id, ItemStatus.Failed, reason)
        return ItemOutcome.Failed(reason)
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val STOP_LENGTH = "length"
        const val STOP_USER = "user"
        const val REASON_LENGTH = FailureReasons.LENGTH
        const val REASON_INVALID_JSON = FailureReasons.INVALID_JSON
        const val REASON_DECODE = FailureReasons.DECODE
        const val REASON_PROMPT = FailureReasons.PROMPT
    }
}
