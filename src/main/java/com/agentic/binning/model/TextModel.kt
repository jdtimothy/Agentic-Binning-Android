package com.agentic.binning.model

/**
 * How the reply must be shaped, in both forms: Claude reads [jsonSchema] (structured output), the
 * local models read [grammar] (GBNF). Each backend uses its own half.
 */
data class ReplyFormat(val jsonSchema: Map<String, Any>, val grammar: String)

/**
 * One reply. [stopReason] is the backend's own word: GenieX gives `eos`, `length` or `user`;
 * Claude gives `end_turn` or `max_tokens`, and `user` when [TextModel.cancel] stopped the call.
 */
data class TextReply(
    val text: String,
    val stopReason: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val millis: Long,
)

/**
 * Why categorizing can't run now; the run ends normally and the items stay uncategorized.
 * [RequestRejected] is a 400 (or a billing error): retrying the same call will not help until
 * something changes, such as the account's credit balance. [TimedOut] is a reply that took longer
 * than the request timeout; a connection that never opened is [NoNetwork].
 */
enum class SortUnavailable {
    NoApiKey, NoNetwork, BadApiKey, RateLimited, ServiceError, TimedOut, RequestRejected, Refused, ModelNotDownloaded, ModelLoadFailed,
}

/** [message] is for the log and never holds the API key; people see [describe] of [reason]. */
class SortingUnavailableException(val reason: SortUnavailable, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The sentence shown to the user for [reason]. */
fun describe(reason: SortUnavailable): String = when (reason) {
    SortUnavailable.NoApiKey -> "Add a Claude API key in Settings to categorize"
    SortUnavailable.NoNetwork -> "No connection; categorizing will try again after the next run"
    SortUnavailable.BadApiKey -> "Claude didn't accept the API key; check it in Settings"
    SortUnavailable.RateLimited -> "Claude is busy; categorizing will try again after the next run"
    SortUnavailable.ServiceError -> "Claude had a problem; categorizing will try again after the next run"
    SortUnavailable.TimedOut -> "Claude took too long; categorizing will try again after the next run"
    SortUnavailable.RequestRejected -> "Claude rejected the request; check your Claude account, for example its credit balance"
    SortUnavailable.Refused -> "Claude declined to categorize some items; they stay uncategorized"
    SortUnavailable.ModelNotDownloaded -> "Download the sorting model in Settings"
    SortUnavailable.ModelLoadFailed -> "The sorting model couldn't start"
}

/** A text-only model that sorts items: a local GenieX model or Claude. */
interface TextModel {
    /** Stored with each sort run, e.g. `LiquidAI/LFM3-1.7B-GGUF:Q4_0` or `claude-sonnet-5-5`. */
    val modelId: String

    /**
     * Loads the model (a no-op for Claude). @throws SortingUnavailableException with
     * [SortUnavailable.ModelNotDownloaded] or [SortUnavailable.ModelLoadFailed].
     */
    suspend fun load()

    /**
     * One single-turn call. [maxTokens] caps a local model's reply; Claude always gets its own cap
     * because its thinking shares it. @throws SortingUnavailableException when the backend can't
     * answer for a reason the user can fix or that will pass (no key, no network, refusal...).
     */
    suspend fun complete(system: String, user: String, format: ReplyFormat, maxTokens: Int): TextReply

    /** Stops a running [complete] early; it then returns with stop reason `user`. */
    suspend fun cancel()

    /** Stops any call and frees the model. Safe to call more than once. */
    suspend fun release()
}
