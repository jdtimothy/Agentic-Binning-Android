package com.agentic.binning.model

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.ErrorType
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext

/**
 * Claude through the official Java SDK. Text only: the caller sends ids, descriptions, tags and
 * category names, never media, paths or file names.
 *
 * Each [complete] reads the current key from [apiKey], creates a client with [clientFactory] and
 * closes it afterwards, so a key changed in Settings applies to the next call. The key never
 * reaches a log or an exception message.
 */
class ClaudeTextModel(
    private val apiKey: suspend () -> String?,
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient,
    private val log: (String) -> Unit = {},
) : TextModel {
    override val modelId: String = MODEL

    /** The request being awaited, for [cancel]. */
    @Volatile
    private var inFlight: CompletableFuture<BetaMessage>? = null

    /**
     * Set by [cancel], cleared when a [complete] starts: a cancel that lands before [inFlight] is set
     * still stops the call that is about to start. Written before [inFlight] is read in [cancel], and
     * read after [inFlight] is written in [complete], so one of the two always sees the other.
     */
    @Volatile
    private var cancelRequested = false

    override suspend fun load() = Unit

    override suspend fun release() = cancel()

    override suspend fun cancel() {
        cancelRequested = true
        inFlight?.cancel(true)
    }

    override suspend fun complete(system: String, user: String, format: ReplyFormat, maxTokens: Int): TextReply {
        cancelRequested = false
        val key = apiKey()?.takeIf { it.isNotBlank() }
            ?: throw SortingUnavailableException(SortUnavailable.NoApiKey, "No Claude API key is saved")
        val params = params(system, user, format)
        val started = System.nanoTime()
        val client = withContext(Dispatchers.IO) { clientFactory(key) }
        try {
            var future: CompletableFuture<BetaMessage>? = null
            val message = try {
                if (cancelRequested) return cancelled(started)
                // The async call returns a future: awaiting it gives way at once to a cancelled
                // coroutine (the future is cancelled too), where a blocking call would hold it until
                // the HTTP request ends. create() itself may throw too, so it sits inside the mapping.
                future = client.async().beta().messages().create(params, REQUEST_OPTIONS)
                inFlight = future
                if (cancelRequested) future.cancel(true)
                future.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // Our own cancellation: let it propagate.
                if (future?.isCancelled != true) throw e
                return cancelled(started)
            } catch (e: Exception) {
                val reason = mapClaudeError(e) ?: throw e
                val what = describeFailure(e, key)
                log("$what -> $reason")
                throw SortingUnavailableException(reason, what, e)
            }
            return reply(message, elapsedMs(started))
        } finally {
            inFlight = null
            withContext(NonCancellable + Dispatchers.IO) { client.close() }
        }
    }

    private fun cancelled(started: Long): TextReply {
        log("cancelled after ${elapsedMs(started)} ms")
        return TextReply("", STOP_USER, 0, 0, elapsedMs(started))
    }

    private fun reply(message: BetaMessage, millis: Long): TextReply {
        // stop_reason before content: a refusal may come with no content or a partial one.
        val stop = message.stopReason().map { it.asString() }.orElse("")
        val usage = message.usage()
        log("completed: stop=$stop model=${message.model().asString()} in=${usage.inputTokens()} out=${usage.outputTokens()} ${millis}ms")
        if (stop == STOP_REFUSAL) {
            val category = message.stopDetails().map { it.toString() }.orElse("none")
            throw SortingUnavailableException(SortUnavailable.Refused, "Claude declined the request (stop details: $category)")
        }
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        return TextReply(text, stop, usage.inputTokens().toInt(), usage.outputTokens().toInt(), millis)
    }

    private fun params(system: String, user: String, format: ReplyFormat): MessageCreateParams {
        val schema = BetaJsonOutputFormat.Schema.builder().apply {
            wireSchema(format.jsonSchema).forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
        }.build()
        // No thinking, temperature, top_p or top_k: this model rejects them. Thinking stays adaptive
        // and shares max_tokens, hence the fixed cap.
        return MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(MAX_TOKENS)
            .system(system)
            .addUserMessage(user)
            .outputConfig(
                BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.MEDIUM)
                    .format(BetaJsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            // Server-side refusal fallbacks. SDK 2.34.0 has neither a `fallbacks` setter nor a
            // constant for this beta, so both go through the SDK's documented extra-body and
            // string-beta escapes.
            .addBeta(FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
    }

    companion object {
        const val MODEL = "claude-sonnet-5-5"
        const val MAX_TOKENS = 16_000L

        /**
         * One non-streaming reply of up to [MAX_TOKENS] (thinking included) must arrive within this.
         * Set on each request, so the SDK's own max_tokens-based default never applies.
         */
        val REQUEST_TIMEOUT: Duration = Duration.ofMinutes(5)

        /** The call is paid: no SDK retries (they would send it again); the next run tries again. */
        const val MAX_RETRIES = 0

        private val TIMEOUT: Timeout = Timeout.builder().read(REQUEST_TIMEOUT).request(REQUEST_TIMEOUT).build()

        private val REQUEST_OPTIONS: RequestOptions = RequestOptions.builder().timeout(TIMEOUT).build()

        /** The most of the server's error message that reaches the log. */
        private const val MAX_SERVER_MESSAGE = 300

        private val whitespaceRun = Regex("""\s+""")
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val STOP_REFUSAL = "refusal"
        const val STOP_USER = "user"

        fun defaultClient(key: String): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(key)
            .maxRetries(MAX_RETRIES)
            .timeout(TIMEOUT)
            .build()

        /**
         * One log line for a failed call: the exception class, and for an API error its status and the
         * server's error type and message, so a rejected request shape can be read from logcat. [key]
         * is blanked out in case the server ever echoes it.
         */
        internal fun describeFailure(e: Throwable, key: String): String {
            val chain = causes(e)
            val base = "Claude call failed: ${e.javaClass.simpleName}"
            val service = chain.filterIsInstance<AnthropicServiceException>().firstOrNull()
                ?: return chain.drop(1).lastOrNull()?.let { "$base (${it.javaClass.simpleName})" } ?: base
            val error = service.body().asObject().orElse(null)?.get("error")?.asObject()?.orElse(null)
            val type = error?.get("type")?.asString()?.orElse(null)
            val message = error?.get("message")?.asString()?.orElse(null)
                ?.replace(key, "[key]")?.replace(whitespaceRun, " ")?.take(MAX_SERVER_MESSAGE)
            return "$base (${service.statusCode()}${type?.let { " $it" }.orEmpty()})${message?.let { ": $it" }.orEmpty()}"
        }

        /**
         * Keywords Claude's structured outputs reject with a 400 (docs, Oct 2026): numeric and string
         * bounds, and array bounds other than `minItems` 0 or 1. The validator still enforces them.
         */
        private val UNSUPPORTED = setOf(
            "maxItems", "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "uniqueItems", "minProperties", "maxProperties",
        )

        /** Maps whose keys are names (of properties or definitions), not keywords. */
        private val NAME_MAPS = setOf("properties", "\$defs", "definitions")

        /** [schema] without the keywords Claude does not accept; property names are never touched. */
        @Suppress("UNCHECKED_CAST")
        fun wireSchema(schema: Map<String, Any>): Map<String, Any> = clean(schema) as Map<String, Any>

        private fun clean(node: Any?): Any? = when (node) {
            is Map<*, *> -> node.entries
                .filter { (k, v) -> k !in UNSUPPORTED && !(k == "minItems" && (v as? Number)?.toInt()?.let { it > 1 } == true) }
                .associate { (k, v) ->
                    k to if (k in NAME_MAPS && v is Map<*, *>) v.mapValues { clean(it.value) } else clean(v)
                }
            is List<*> -> node.map { clean(it) }
            else -> node
        }

        private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000
    }
}

/**
 * The reason behind a failed Claude call, most specific first, looking through wrappers such as
 * `CompletionException`. Null when it isn't an API or network failure (a bug: let it propagate).
 */
internal fun mapClaudeError(t: Throwable): SortUnavailable? {
    val chain = causes(t)
    chain.filterIsInstance<AnthropicServiceException>().firstOrNull()?.let { e ->
        return when {
            e is UnauthorizedException || e is PermissionDeniedException -> SortUnavailable.BadApiKey
            e is RateLimitException -> SortUnavailable.RateLimited
            e is InternalServerException -> SortUnavailable.ServiceError
            // A 400 (a request shape the API rejects, a low credit balance) or a billing error: the
            // same call fails again until something changes, so it never reads as a passing problem.
            e is BadRequestException || e.errorType().orElse(null) == ErrorType.BILLING_ERROR -> SortUnavailable.RequestRejected
            // Other 5xx (529 overloaded) and 4xx we didn't expect: nothing the user can fix, so
            // retry on the next run rather than fail anything.
            else -> SortUnavailable.ServiceError
        }
    }
    // A connection that never opened is no network; a reply that didn't arrive in time is a timeout.
    if (chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }) return SortUnavailable.NoNetwork
    chain.firstOrNull { it is InterruptedIOException }?.let { timeout ->
        val connecting = timeout.message.orEmpty().contains("connect", ignoreCase = true)
        return if (connecting) SortUnavailable.NoNetwork else SortUnavailable.TimedOut
    }
    if (chain.any { it is AnthropicIoException || it is IOException }) return SortUnavailable.NoNetwork
    return if (t is AnthropicException) SortUnavailable.ServiceError else null
}

/** [t] and its causes, outermost first, at most 8 deep. */
private fun causes(t: Throwable): List<Throwable> = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8).toList()
