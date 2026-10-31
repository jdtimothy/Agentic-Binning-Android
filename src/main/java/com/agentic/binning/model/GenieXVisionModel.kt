package com.agentic.binning.model

import android.util.Log
import com.agentic.binning.analysis.AnalysisPrompt
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.analysis.PromptAssembly
import com.agentic.binning.db.ItemKind
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import java.io.File
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
 * LFM3-VL on the Hexagon NPU through GenieX's llama.cpp runtime. Each [generate] is a fresh
 * single-turn call: the media is spliced into the templated prompt (GenieX's template step would
 * hoist every image ahead of the text) and the image paths are injected in marker order.
 */
class GenieXVisionModel(
    private val spec: ModelSpec,
    private val useGrammar: Boolean = true,
) : VisionModel {
    override val modelId: String = "${spec.repo}:${spec.precision}"

    @Volatile
    override var imageEdge: Int = FALLBACK_IMAGE_EDGE
        private set

    /** Serialises load, generate and destroy; [cancel] and [release] stop the stream without it. */
    private val lock = Mutex()

    /**
     * Serialises stopStream with destroy, so a [cancel] can never reach a handle [release] is freeing.
     * Order when both are held: [lock], then this.
     */
    private val nativeLock = Mutex()

    @Volatile
    private var wrapper: VlmWrapper? = null

    override suspend fun load() = lock.withLock { withContext(Dispatchers.IO) { loadLocked() } }

    private suspend fun loadLocked() {
        if (wrapper != null) return
        val paths = ModelManagerWrapper.getPaths(spec.repo)
            ?: throw VisionModelException("${spec.displayName} is not downloaded")
        val mmproj = paths.mmproj_path?.takeIf { it.isNotEmpty() }
            ?: throw VisionModelException("${spec.displayName} has no vision projector (mmproj)")
        val vision = GgufVisionReader.read(File(mmproj))
        imageEdge = vision?.imageSize ?: FALLBACK_IMAGE_EDGE
        Log.d(
            TAG,
            vision?.let { "vision tower ${it.imageSize}px patch ${it.patchSize} merge ${it.spatialMergeSize} -> ${it.tokenCount} tokens" }
                ?: "no vision config in mmproj; using $FALLBACK_IMAGE_EDGE px",
        )
        val input = VlmCreateInput(
            model_path = paths.model_path,
            mmproj_path = mmproj,
            config = ModelConfig(nCtx = N_CTX, nThreads = 4, nBatch = 2048, nUBatch = 512, nGpuLayers = 999),
            runtime_id = paths.runtime_id?.takeIf { it.isNotEmpty() } ?: "llama_cpp",
            compute_unit = COMPUTE_UNIT,
        )
        wrapper = VlmWrapper.builder().vlmCreateInput(input).build().getOrElse {
            throw VisionModelException("Loading ${spec.displayName} on $COMPUTE_UNIT failed: ${it.message}", it)
        }
        Log.d(TAG, "loaded $modelId on $COMPUTE_UNIT (runtime ${input.runtime_id}, nCtx $N_CTX)")
    }

    override suspend fun generate(media: PreparedMedia, onToken: (String) -> Unit): VisionReply =
        lock.withLock { withContext(Dispatchers.IO) { generateLocked(media, onToken) } }

    /**
     * @throws IllegalStateException if the prompt cannot be assembled or its media markers do not
     * match the images.
     */
    private suspend fun generateLocked(media: PreparedMedia, onToken: (String) -> Unit): VisionReply {
        val vlm = wrapper ?: throw VisionModelException("$modelId is not loaded")
        vlm.reset()

        val messages = arrayOf(
            VlmChatMessage(role = "system", contents = listOf(VlmContent("text", AnalysisPrompt.SYSTEM))),
            VlmChatMessage(role = "user", contents = listOf(VlmContent("text", PromptAssembly.PLACEHOLDER))),
        )
        val templated = vlm.applyChatTemplate(messages, null, false).getOrElse {
            throw VisionModelException("Chat template failed: ${it.message}", it)
        }
        val mediaText = when (media.kind) {
            ItemKind.Photo -> PromptAssembly.photoMediaText()
            ItemKind.Video -> PromptAssembly.videoMediaText(media.frameTimesMs)
        }
        val prompt = PromptAssembly.splice(templated.formattedText, mediaText, AnalysisPrompt.instruction(media.kind))
        val markers = prompt.split(PromptAssembly.MEDIA_MARKER).size - 1
        check(markers == media.imagePaths.size) { "Prompt has $markers media markers for ${media.imagePaths.size} images" }
        Log.d(TAG, "prompt as sent (${prompt.length} chars): ${prompt.take(PROMPT_LOG_CHARS)}")

        val sampler = SamplerConfig().apply {
            temperature = 0.1f
            topK = 50
            topP = 1.0f
            minP = 1e-6f // 0 means "SDK default" in SamplerConfig; this turns min_p off in practice.
            repetitionPenalty = 1.1f
            if (useGrammar) grammarString = AnalysisPrompt.grammar(media.kind)
        }
        val base = GenerationConfig().apply {
            maxTokens = when (media.kind) {
                ItemKind.Photo -> AnalysisPrompt.MAX_TOKENS_PHOTO
                ItemKind.Video -> AnalysisPrompt.MAX_TOKENS_VIDEO
            }
            samplerConfig = sampler
        }
        // Only image parts, in the same order as the markers in `prompt`.
        val config = vlm.injectMediaPathsToConfig(
            arrayOf(VlmChatMessage(role = "user", contents = media.imagePaths.map { VlmContent("image", it) })),
            base,
        )

        val text = StringBuilder()
        var profile: ProfilingData? = null
        var failure: Throwable? = null
        coroutineScope {
            // The SDK's callbackFlow blocks in native generate, and a cancelled collect waits for it to
            // return, so a catch after collect would come too late. This child sees the cancellation
            // at once and stops the native stream; the lock is held until generate really returns.
            var streaming = true
            val stopOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (streaming) withContext(NonCancellable) { nativeLock.withLock { vlm.stopStream() } }
                }
            }
            vlm.generateStreamFlow(prompt, config).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> {
                        text.append(result.text)
                        onToken(result.text)
                    }
                    is LlmStreamResult.Completed -> profile = result.profile
                    is LlmStreamResult.Error -> failure = result.throwable
                }
            }
            streaming = false
            stopOnCancel.cancel()
        }
        failure?.let { throw VisionModelException("Generation failed: ${it.message}", it) }
        val p = profile ?: throw VisionModelException("Generation ended without a result")
        Log.d(
            TAG,
            String.format(
                Locale.US,
                "completed: stop=%s ttft=%.0fms media=%.0fms prompt=%d tok (%.1f tok/s) generated=%d tok (%.1f tok/s)",
                p.stopReason, p.ttftMs, p.mediaMs, p.promptTokens, p.prefillSpeed, p.generatedTokens, p.decodingSpeed,
            ),
        )
        return VisionReply(
            text = text.toString(),
            profile = VisionProfile(
                stopReason = p.stopReason,
                ttftMs = p.ttftMs,
                promptTokens = p.promptTokens.toInt(),
                generatedTokens = p.generatedTokens.toInt(),
                decodingSpeed = p.decodingSpeed,
            ),
        )
    }

    override suspend fun cancel() = stopStream()

    override suspend fun release() {
        stopStream()
        lock.withLock {
            nativeLock.withLock {
                val vlm = wrapper ?: return@withLock
                wrapper = null
                withContext(Dispatchers.IO + NonCancellable) { vlm.destroy() }
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
        const val TAG = "VisionModel"
        const val FALLBACK_IMAGE_EDGE = 512
        const val N_CTX = 16384
        const val PROMPT_LOG_CHARS = 2000
        val COMPUTE_UNIT: String = ComputeUnitValue.NPU.value ?: "npu"
    }
}
