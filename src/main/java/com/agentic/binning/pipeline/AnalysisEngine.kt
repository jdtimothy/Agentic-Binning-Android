package com.agentic.binning.pipeline

import com.agentic.binning.analysis.AnalysisParser
import com.agentic.binning.analysis.AnalysisResult
import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.model.VisionProfile
import java.io.FileNotFoundException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Turns an item into the images the model reads ([com.agentic.binning.analysis.MediaPreparer] in the app). Blocking. */
interface Preparer {
    /**
     * @throws FileNotFoundException if the item's file is gone.
     * @throws Exception (typically `MediaDecodeException`) if it cannot be decoded.
     */
    fun prepare(item: ItemEntity): PreparedMedia

    /** Deletes the work files [prepare] wrote for [itemId]; never touches the item's own file. */
    fun cleanup(itemId: Long)
}

/** Item counts for progress: [pending] includes the item being analyzed; [analyzed] counts every item past analysis. */
data class ItemCounts(val pending: Int, val analyzed: Int, val total: Int)

/** The pipeline's view of the current project's database. */
interface AnalysisStore {
    suspend fun nextPending(): ItemEntity?
    suspend fun setStatus(id: Long, status: ItemStatus, reason: String? = null)
    suspend fun resetStatus(from: ItemStatus, to: ItemStatus): Int

    /** Stores the analysis and marks the item Analyzed. */
    suspend fun save(itemId: Long, result: AnalysisResult, raw: String, modelId: String, profile: VisionProfile)
    suspend fun counts(): ItemCounts
}

sealed interface ItemOutcome {
    data class Analyzed(val result: AnalysisResult) : ItemOutcome
    data class Failed(val reason: String) : ItemOutcome
    object Missing : ItemOutcome
    object Cancelled : ItemOutcome
}

/**
 * Analyzes one item: prepare, generate (retrying once on a `length` stop or unparseable JSON), parse
 * and save. Every outcome except [ItemOutcome.Cancelled] is written to [store]; Cancelled leaves the
 * status for the caller. The preparer's work files are always cleaned up.
 */
class AnalysisEngine(
    private val model: VisionModel,
    private val preparer: Preparer,
    private val store: AnalysisStore,
    /** Where the blocking [Preparer.prepare] runs; the app passes `Dispatchers.IO`. */
    private val prepareContext: CoroutineContext = EmptyCoroutineContext,
) {
    /** Profile of the most recent reply, including failed attempts. */
    @Volatile
    var lastProfile: VisionProfile? = null
        private set

    suspend fun analyze(item: ItemEntity, onPartial: (PartialAnalysis) -> Unit): ItemOutcome =
        analyze(item, onDescribing = {}, onPartial = onPartial)

    /** [onDescribing] is called once the media is prepared, just before the model starts. */
    suspend fun analyze(
        item: ItemEntity,
        onDescribing: () -> Unit,
        onPartial: (PartialAnalysis) -> Unit,
    ): ItemOutcome {
        try {
            val media = try {
                withContext(prepareContext) { preparer.prepare(item) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: FileNotFoundException) {
                store.setStatus(item.id, ItemStatus.Missing)
                return ItemOutcome.Missing
            } catch (_: Exception) {
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
                } catch (_: IllegalStateException) {
                    return fail(item, REASON_PROMPT) // Prompt assembly or the media-marker check.
                } catch (_: VisionModelException) {
                    return fail(item, REASON_MODEL)
                }
                lastProfile = reply.profile
                when (reply.profile.stopReason) {
                    STOP_USER -> return ItemOutcome.Cancelled
                    STOP_LENGTH -> reason = REASON_LENGTH
                    else -> {
                        val result = AnalysisParser.parse(reply.text, item.kind)
                        if (result != null) {
                            store.save(item.id, result, reply.text, model.modelId, reply.profile)
                            return ItemOutcome.Analyzed(result)
                        }
                        reason = REASON_INVALID_JSON
                    }
                }
            }
            return fail(item, reason)
        } finally {
            preparer.cleanup(item.id)
        }
    }

    private suspend fun fail(item: ItemEntity, reason: String): ItemOutcome.Failed {
        store.setStatus(item.id, ItemStatus.Failed, reason)
        return ItemOutcome.Failed(reason)
    }

    companion object {
        const val MAX_ATTEMPTS = 2
        const val STOP_LENGTH = "length"
        const val STOP_USER = "user"
        const val REASON_LENGTH = "length"
        const val REASON_INVALID_JSON = "invalid-json"
        const val REASON_DECODE = "decode"
        const val REASON_PROMPT = "prompt"
        const val REASON_MODEL = "model"
    }
}
