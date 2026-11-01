package com.agentic.binning.model

import android.util.Log
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import java.util.Locale
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A local text model on the Hexagon NPU through GenieX's `LlmWrapper` (LFM3-1.7B). Each
 * [complete] is a fresh single-turn call with the reply held to [ReplyFormat.grammar].
 *
 * Locking mirrors [GenieXVisionModel]: [lock] serialises load, complete and destroy; [cancel] and
 * [release] stop the stream without it, under [nativeLock].
 */
class GenieXTextModel(
    private val spec: ModelSpec,
    /** Context for `llama_cpp` models; AI Hub `qairt` bundles have a fixed one and get 0. */
    private val nCtx: Int,
) : TextModel {
    override val modelId: String = "${spec.repo}:${spec.precision}"

    private val lock = Mutex()

    /** Serialises stopStream with destroy. Order when both are held: [lock], then this. */
    private val nativeLock = Mutex()

    @Volatile
    private var wrapper: LlmWrapper? = null

    /** `qairt` ignores nCtx and throws on a grammar (Plan 2a spike), so it gets neither. */
    @Volatile
    private var qairt = false

    override suspend fun load() = lock.withLock { withContext(Dispatchers.IO) { loadLocked() } }

    private suspend fun loadLocked() {
        if (wrapper != null) return
        val paths = ModelManagerWrapper.getPaths(spec.repo)
            ?: throw SortingUnavailableException(SortUnavailable.ModelNotDownloaded, "${spec.displayName} is not downloaded")
        val runtime = paths.runtime_id?.takeIf { it.isNotEmpty() } ?: "llama_cpp"
        val isQairt = runtime == "qairt"
        val config = if (isQairt) {
            ModelConfig(nCtx = 0, nGpuLayers = 0)
        } else {
            ModelConfig(nCtx = nCtx, nThreads = 4, nBatch = 2048, nUBatch = 512, nGpuLayers = 999)
        }
        val input = LlmCreateInput(
            model_path = paths.model_path,
            tokenizer_path = paths.tokenizer_path,
            config = config,
            runtime_id = runtime,
            compute_unit = COMPUTE_UNIT,
        )
        val started = System.nanoTime()
        wrapper = LlmWrapper.builder().llmCreateInput(input).build().getOrElse {
            Log.e(TAG, "loading $modelId on $COMPUTE_UNIT (runtime $runtime) failed", it)
            throw SortingUnavailableException(SortUnavailable.ModelLoadFailed, "Loading ${spec.displayName} failed: ${it.message}", it)
        }
        qairt = isQairt
        Log.d(TAG, "loaded $modelId on $COMPUTE_UNIT (runtime $runtime, nCtx ${config.nCtx}) in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    override suspend fun complete(system: String, user: String, format: ReplyFormat, maxTokens: Int): TextReply =
        lock.withLock { withContext(Dispatchers.IO) { completeLocked(system, user, format, maxTokens) } }

    private suspend fun completeLocked(system: String, user: String, format: ReplyFormat, maxTokens: Int): TextReply {
        val llm = checkNotNull(wrapper) { "$modelId is not loaded" }
        val started = System.nanoTime()
        llm.reset()
        val messages = arrayOf(ChatMessage("system", system), ChatMessage("user", user))
        // enableThinking = false: no <think> block in the reply (spike).
        val prompt = llm.applyChatTemplate(messages, null, false).getOrElse {
            throw IllegalStateException("Chat template failed: ${it.message}", it)
        }.formattedText
        Log.d(TAG, "prompt as sent (${prompt.length} chars, maxTokens $maxTokens): ${prompt.take(PROMPT_LOG_CHARS)}")

        val sampler = SamplerConfig().apply {
            // The general preset from the LFM3 card.
            temperature = 0.1f
            topK = 50
            topP = 1.0f
            minP = 1e-6f // 0 means "SDK default" in SamplerConfig; this turns min_p off in practice.
            repetitionPenalty = 1.1f
            if (!qairt) grammarString = format.grammar
        }
        val config = GenerationConfig().apply {
            this.maxTokens = maxTokens
            samplerConfig = sampler
        }

        val text = StringBuilder()
        var profile: ProfilingData? = null
        var failure: Throwable? = null
        coroutineScope {
            // As in GenieXVisionModel: the collect can't see a cancellation until native generate
            // returns, so this child stops the stream as soon as the caller is cancelled.
            var streaming = true
            val stopOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (streaming) withContext(NonCancellable) { nativeLock.withLock { llm.stopStream() } }
                }
            }
            llm.generateStreamFlow(prompt = prompt, config = config).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> text.append(result.text)
                    is LlmStreamResult.Completed -> profile = result.profile
                    is LlmStreamResult.Error -> failure = result.throwable
                }
            }
            streaming = false
            stopOnCancel.cancel()
        }
        failure?.let { throw IllegalStateException("Generation failed: ${it.message}", it) }
        val p = profile ?: throw IllegalStateException("Generation ended without a result")
        val millis = (System.nanoTime() - started) / 1_000_000
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "completed: stop=%s ttft=%.0fms prompt=%d tok (%.1f tok/s) generated=%d tok (%.1f tok/s) %d ms",
                p.stopReason, p.ttftMs, p.promptTokens, p.prefillSpeed, p.generatedTokens, p.decodingSpeed, millis,
            ),
        )
        return TextReply(text.toString(), p.stopReason, p.promptTokens.toInt(), p.generatedTokens.toInt(), millis)
    }

    override suspend fun cancel() = stopStream()

    /** Runs whole even for a cancelled caller (Stop, project switch), so the NPU model is always freed. */
    override suspend fun release(): Unit = withContext(NonCancellable) {
        stopStream()
        lock.withLock {
            nativeLock.withLock {
                val llm = wrapper ?: return@withLock
                wrapper = null
                qairt = false
                withContext(Dispatchers.IO) { llm.destroy() }
                Log.d(TAG, "released $modelId")
            }
        }
    }

    private suspend fun stopStream() {
        nativeLock.withLock {
            withContext(Dispatchers.IO) { wrapper?.stopStream() }
        }
    }

    private companion object {
        const val TAG = "TextModel"
        const val PROMPT_LOG_CHARS = 2000
        val COMPUTE_UNIT: String = ComputeUnitValue.NPU.value ?: "npu"
    }
}
