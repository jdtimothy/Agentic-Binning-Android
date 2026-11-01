package com.agentic.binning.model

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.core.RequestOptions
import com.anthropic.core.jsonMapper
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnexpectedStatusCodeException
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams
import java.io.InterruptedIOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudeErrorMappingTest {

    // --- Mapping table -------------------------------------------------------------------------

    @Test fun unauthorized401_isBadApiKey() =
        assertEquals(SortUnavailable.BadApiKey, mapClaudeError(UnauthorizedException.builder().headers(noHeaders).body(errorBody).build()))

    @Test fun permissionDenied403_isBadApiKey() =
        assertEquals(SortUnavailable.BadApiKey, mapClaudeError(PermissionDeniedException.builder().headers(noHeaders).body(errorBody).build()))

    @Test fun rateLimit429_isRateLimited() =
        assertEquals(SortUnavailable.RateLimited, mapClaudeError(RateLimitException.builder().headers(noHeaders).body(errorBody).build()))

    @Test fun internalServer500_isServiceError() = assertEquals(
        SortUnavailable.ServiceError,
        mapClaudeError(InternalServerException.builder().statusCode(500).headers(noHeaders).body(errorBody).build()),
    )

    @Test fun overloaded529_isServiceError() = assertEquals(
        SortUnavailable.ServiceError,
        mapClaudeError(UnexpectedStatusCodeException.builder().statusCode(529).headers(noHeaders).body(errorBody).build()),
    )

    @Test fun badRequest400_isRequestRejected() = assertEquals(
        SortUnavailable.RequestRejected,
        mapClaudeError(BadRequestException.builder().headers(noHeaders).body(errorBody).build()),
    )

    @Test fun billingError_isRequestRejected() = assertEquals(
        SortUnavailable.RequestRejected,
        mapClaudeError(UnexpectedStatusCodeException.builder().statusCode(402).headers(noHeaders).body(errorBodyOf("billing_error", "Billing")).build()),
    )

    @Test fun badRequest400_wrappedInCompletionException_isRequestRejected() = assertEquals(
        SortUnavailable.RequestRejected,
        mapClaudeError(CompletionException(BadRequestException.builder().headers(noHeaders).body(errorBody).build())),
    )

    @Test fun ioException_isNoNetwork() =
        assertEquals(SortUnavailable.NoNetwork, mapClaudeError(AnthropicIoException("Request failed", UnknownHostException("api.anthropic.com"))))

    @Test fun unknownHost_isNoNetwork() =
        assertEquals(SortUnavailable.NoNetwork, mapClaudeError(UnknownHostException("api.anthropic.com")))

    @Test fun readTimeout_isTimedOut() =
        assertEquals(SortUnavailable.TimedOut, mapClaudeError(SocketTimeoutException("timeout")))

    /** OkHttp's call timeout, wrapped by the SDK's OkHttp client. */
    @Test fun callTimeout_wrappedByTheSdk_isTimedOut() = assertEquals(
        SortUnavailable.TimedOut,
        mapClaudeError(CompletionException(AnthropicIoException("Request failed", InterruptedIOException("timeout")))),
    )

    @Test fun connectTimeout_isNoNetwork() =
        assertEquals(SortUnavailable.NoNetwork, mapClaudeError(AnthropicIoException("Request failed", SocketTimeoutException("connect timed out"))))

    @Test fun connectFailure_isNoNetwork() =
        assertEquals(SortUnavailable.NoNetwork, mapClaudeError(AnthropicIoException("Request failed", ConnectException("Failed to connect"))))

    @Test fun wrappedInCompletionException_isUnwrapped() = assertEquals(
        SortUnavailable.RateLimited,
        mapClaudeError(CompletionException(RateLimitException.builder().headers(noHeaders).body(errorBody).build())),
    )

    @Test fun unrelatedException_isNotMapped() = assertEquals(null, mapClaudeError(IllegalStateException("bug")))

    // --- describe --------------------------------------------------------------------------------

    @Test fun describe_coversEveryReason() {
        val expected = mapOf(
            SortUnavailable.NoApiKey to "Add a Claude API key in Settings to categorize",
            SortUnavailable.NoNetwork to "No connection; categorizing will try again after the next run",
            SortUnavailable.BadApiKey to "Claude didn't accept the API key; check it in Settings",
            SortUnavailable.RateLimited to "Claude is busy; categorizing will try again after the next run",
            SortUnavailable.ServiceError to "Claude had a problem; categorizing will try again after the next run",
            SortUnavailable.TimedOut to "Claude took too long; categorizing will try again after the next run",
            SortUnavailable.RequestRejected to "Claude rejected the request; check your Claude account, for example its credit balance",
            SortUnavailable.Refused to "Claude declined to categorize some items; they stay uncategorized",
            SortUnavailable.ModelNotDownloaded to "Download the sorting model in Settings",
            SortUnavailable.ModelLoadFailed to "The sorting model couldn't start",
        )
        assertEquals(SortUnavailable.entries.toSet(), expected.keys)
        for (reason in SortUnavailable.entries) assertEquals(reason.name, expected.getValue(reason), describe(reason))
    }

    // --- complete() through a fake client --------------------------------------------------------

    @Test fun noKey_throwsNoApiKey_withoutCreatingAClient() = runTest {
        var created = 0
        val model = ClaudeTextModel(apiKey = { null }, clientFactory = { created++; FakeClient { CompletableFuture() }.client })
        val e = expectUnavailable { model.complete("sys", "user", format, 900) }
        assertEquals(SortUnavailable.NoApiKey, e.reason)
        assertEquals(0, created)
    }

    @Test fun serviceError_throwsMappedReason_closesClient_andHidesKey() = runTest {
        val fake = FakeClient { CompletableFuture.failedFuture(UnauthorizedException.builder().headers(noHeaders).body(errorBody).build()) }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        val e = expectUnavailable { model.complete("sys", "user", format, 900) }
        assertEquals(SortUnavailable.BadApiKey, e.reason)
        assertFalse(e.message.orEmpty(), e.message.orEmpty().contains(SECRET))
        assertEquals(1, fake.closed)
    }

    @Test fun badRequest_logsStatusAndServerError_butNeverTheKey() = runTest {
        val body = errorBodyOf("invalid_request_error", "Your credit balance is too low (key $SECRET)")
        val fake = FakeClient { CompletableFuture.failedFuture(BadRequestException.builder().headers(noHeaders).body(body).build()) }
        val logged = mutableListOf<String>()
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client }, log = { logged += it })
        val e = expectUnavailable { model.complete("sys", "user", format, 900) }
        assertEquals(SortUnavailable.RequestRejected, e.reason)
        val line = logged.single()
        for (text in listOf(line, e.message.orEmpty())) {
            assertTrue(text, text.contains("400"))
            assertTrue(text, text.contains("invalid_request_error"))
            assertTrue(text, text.contains("Your credit balance is too low"))
            assertFalse(text, text.contains(SECRET))
        }
    }

    @Test fun request_hasAnExplicitTimeoutOfFiveMinutes() = runTest {
        val fake = FakeClient { CompletableFuture.completedFuture(message(stopReason = "end_turn", text = REPLY)) }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        model.complete("sys", "user", format, 900)
        val timeout = checkNotNull(checkNotNull(fake.options).timeout)
        assertEquals(Duration.ofMinutes(5), timeout.request())
        assertEquals(Duration.ofMinutes(5), timeout.read())
    }

    @Test fun refusal_throwsRefused() = runTest {
        val fake = FakeClient { CompletableFuture.completedFuture(message(stopReason = "refusal", text = null)) }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        assertEquals(SortUnavailable.Refused, expectUnavailable { model.complete("sys", "user", format, 900) }.reason)
        assertEquals(1, fake.closed)
    }

    @Test fun success_returnsTextAndUsage_andClosesClient() = runTest {
        val fake = FakeClient { CompletableFuture.completedFuture(message(stopReason = "end_turn", text = REPLY)) }
        var keyUsed: String? = null
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { keyUsed = it; fake.client })
        val reply = model.complete("the system", "the user", format, 900)
        assertEquals(REPLY, reply.text)
        assertEquals("end_turn", reply.stopReason)
        assertEquals(1234, reply.inputTokens)
        assertEquals(56, reply.outputTokens)
        assertEquals(SECRET, keyUsed)
        assertEquals(1, fake.closed)
    }

    @Suppress("DEPRECATION") // temperature, topP and topK: checked absent on purpose.
    @Test fun request_followsTheGlobalConstraints() = runTest {
        val fake = FakeClient { CompletableFuture.completedFuture(message(stopReason = "end_turn", text = REPLY)) }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        model.complete("the system", "the user", format, 900)
        val p = checkNotNull(fake.params)
        assertEquals("claude-sonnet-5-5", p.model().asString())
        assertEquals(16000L, p.maxTokens())
        assertFalse(p.thinking().isPresent)
        assertFalse(p.temperature().isPresent)
        assertFalse(p.topP().isPresent)
        assertFalse(p.topK().isPresent)
        val config = p.outputConfig().get()
        assertEquals(BetaOutputConfig.Effort.MEDIUM, config.effort().get())
        val schemaJson = jsonMapper().writeValueAsString(config.format().get().schema()._additionalProperties())
        assertTrue(schemaJson, schemaJson.contains("\"assignments\""))
        assertFalse(schemaJson, schemaJson.contains("maxItems"))
        assertTrue(p.betas().get().map { it.asString() }.contains("server-side-fallback-2026-07-01"))
        assertEquals(JsonValue.from("default"), p._additionalBodyProperties()["fallbacks"])
        val sent = jsonMapper().writeValueAsString(p.messages())
        assertTrue(sent, sent.contains("the user"))
        assertTrue(p.system().get().toString().contains("the system"))
    }

    @Test fun cancel_stopsTheInFlightCall_withStopReasonUser() = runTest {
        val pending = CompletableFuture<BetaMessage>()
        val fake = FakeClient { pending }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        val call = async(start = CoroutineStart.UNDISPATCHED) { model.complete("sys", "user", format, 900) }
        while (fake.params == null) yield()
        model.cancel()
        val reply = call.await()
        assertEquals("user", reply.stopReason)
        assertEquals("", reply.text)
        assertTrue(pending.isCancelled)
        assertEquals(1, fake.closed)
    }

    @Test fun synchronousThrowFromCreate_isMapped_andClosesClient() = runTest {
        val fake = FakeClient { throw RateLimitException.builder().headers(noHeaders).body(errorBody).build() }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        assertEquals(SortUnavailable.RateLimited, expectUnavailable { model.complete("sys", "user", format, 900) }.reason)
        assertEquals(1, fake.closed)
    }

    @Test fun cancelDuringCreate_beforeInFlightIsSet_stillCancelsTheCall() = runTest {
        val pending = CompletableFuture<BetaMessage>()
        lateinit var model: ClaudeTextModel
        // cancel() lands while create() runs, before complete() has stored the future.
        val fake = FakeClient { runBlocking { model.cancel() }; pending }
        model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        val reply = model.complete("sys", "user", format, 900)
        assertEquals("user", reply.stopReason)
        assertTrue(pending.isCancelled)
        assertEquals(1, fake.closed)
    }

    @Test fun cancelBeforeTheCallStarts_skipsIt() = runTest {
        lateinit var model: ClaudeTextModel
        val fake = FakeClient { CompletableFuture.completedFuture(message(stopReason = "end_turn", text = REPLY)) }
        // cancel() lands after complete() began but before the request exists.
        model = ClaudeTextModel(apiKey = { model.cancel(); SECRET }, clientFactory = { fake.client })
        val reply = model.complete("sys", "user", format, 900)
        assertEquals("user", reply.stopReason)
        assertEquals(null, fake.params)
    }

    @Test fun cancelBeforeComplete_doesNotCancelTheNextCall() = runTest {
        val fake = FakeClient { CompletableFuture.completedFuture(message(stopReason = "end_turn", text = REPLY)) }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        model.cancel()
        assertEquals("end_turn", model.complete("sys", "user", format, 900).stopReason)
    }

    @Test fun coroutineCancellation_cancelsTheFuture() = runTest {
        val pending = CompletableFuture<BetaMessage>()
        val fake = FakeClient { pending }
        val model = ClaudeTextModel(apiKey = { SECRET }, clientFactory = { fake.client })
        val call = async(start = CoroutineStart.UNDISPATCHED) { model.complete("sys", "user", format, 900) }
        while (fake.params == null) yield()
        call.cancel()
        call.join()
        assertTrue(call.isCancelled)
        assertTrue(pending.isCancelled)
        assertEquals(1, fake.closed)
    }

    // --- Schema sent on the wire ------------------------------------------------------------------

    @Test fun wireSchema_dropsUnsupportedKeywords_butKeepsPropertiesNamedLikeThem() {
        val schema: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "list" to mapOf("type" to "array", "items" to mapOf("type" to "string", "maxLength" to 4), "maxItems" to 12, "minItems" to 1),
                "maxItems" to mapOf("type" to "integer", "minimum" to 0),
                "many" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "minItems" to 3),
            ),
            "required" to listOf("list", "maxItems", "many"),
            "additionalProperties" to false,
        )
        val expected: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "list" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "minItems" to 1),
                "maxItems" to mapOf("type" to "integer"),
                "many" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            ),
            "required" to listOf("list", "maxItems", "many"),
            "additionalProperties" to false,
        )
        assertEquals(expected, ClaudeTextModel.wireSchema(schema))
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private suspend fun expectUnavailable(block: suspend () -> Unit): SortingUnavailableException {
        try {
            block()
        } catch (e: SortingUnavailableException) {
            return e
        }
        fail("Expected SortingUnavailableException")
        throw AssertionError()
    }

    /**
     * An [AnthropicClient] built from dynamic proxies: every service getter returns another proxy,
     * `create` on the async beta messages service records its params and returns [respond]'s future,
     * and `close` is counted.
     */
    private class FakeClient(private val respond: () -> CompletableFuture<BetaMessage>) {
        @Volatile var params: MessageCreateParams? = null
        @Volatile var options: RequestOptions? = null
        @Volatile var closed = 0
        val client: AnthropicClient = proxy(AnthropicClient::class.java)

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(type: Class<T>): T {
            val handler = InvocationHandler { self, method, args ->
                when {
                    method.name == "create" && args?.firstOrNull() is MessageCreateParams -> {
                        params = args[0] as MessageCreateParams
                        options = args.getOrNull(1) as? RequestOptions
                        respond()
                    }
                    method.name == "close" -> { closed++; null }
                    method.name == "toString" -> "Fake${type.simpleName}"
                    method.name == "hashCode" -> System.identityHashCode(self)
                    method.name == "equals" -> self === args?.firstOrNull()
                    method.returnType.isInterface -> proxy(method.returnType)
                    else -> throw UnsupportedOperationException("${type.simpleName}.${method.name}")
                }
            }
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
        }
    }

    private companion object {
        const val SECRET = "sk-ant-test-not-a-real-key-1234"
        const val REPLY = """{"new_categories":[],"assignments":[]}"""
        val noHeaders: Headers = Headers.builder().build()
        val errorBody: JsonValue = JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to "x", "message" to "y")))

        fun errorBodyOf(type: String, message: String): JsonValue =
            JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to message)))
        val format = ReplyFormat(
            jsonSchema = com.agentic.binning.sorting.SortSchema.assignJsonSchema(),
            grammar = "root ::= \"{}\"",
        )

        fun message(stopReason: String, text: String?): BetaMessage {
            val content = if (text == null) "[]" else """[{"type":"text","text":${JsonValue.from(text).let { jsonMapper().writeValueAsString(it) }},"citations":null}]"""
            val json = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5",
                 "content":$content,"stop_reason":"$stopReason","stop_sequence":null,"stop_details":null,
                 "container":null,"context_management":null,
                 "usage":{"input_tokens":1234,"output_tokens":56,"cache_creation_input_tokens":0,"cache_read_input_tokens":0}}
            """.trimIndent()
            return jsonMapper().readValue(json, BetaMessage::class.java)
        }
    }
}
