package core.ems.service.ai

import core.db.AiProviderType

/**
 * EZ-1711. The one seam between AI features and AI vendors.
 *
 * Feature code (student explanations today, teacher drafting later) depends on this interface and
 * nothing else — never on a vendor SDK, never on a wire format. The reason is not tidiness: the
 * provider that this abstraction exists for is a future retrieval-augmented service of our own,
 * whose request shape and auth look nothing like a chat-completions call, and it has to slot in
 * without a feature noticing.
 *
 * Deliberately narrow. One system prompt, one user message, one text answer. No tools, no
 * streaming, no structured output — each of those is a real design decision for a later issue, and
 * an interface that pre-empts them gets them wrong.
 */
interface AiProvider {
    val type: AiProviderType

    /** The model this provider was configured to ask for. */
    val model: String

    /** Blocking. Throws [AiProviderException] for anything that is not a usable answer. */
    fun complete(request: AiCompletionRequest): AiCompletionResult
}

/** What a course has configured — [apiKey] is the teacher's own, see [core.db.Course.aiApiKey]. */
data class AiProviderConfig(
    val type: AiProviderType,
    val apiKey: String,
    val baseUrl: String?,
    val model: String,
)

data class AiCompletionRequest(
    val system: String,
    val user: String,
    /**
     * A backstop, not a budget: a ceiling is free until it is hit. Thinking counts against it,
     * which is why it is well above the five sentences asked for — at low effort the thinking is
     * short, and a request that still runs into this is a runaway, better cut than paid for.
     */
    val maxTokens: Int = 4_000,
)

data class AiCompletionResult(
    val text: String,
    /** What the provider says it ran, which can differ from what was asked for (aliases). */
    val model: String,
    val tokensIn: Int?,
    val tokensOut: Int?,
    /** The verbatim response body, for the audit row. */
    val rawResponse: String,
)

/**
 * Anything the provider did that is not an answer: HTTP errors, timeouts, a refusal, a body with
 * no text in it. [rawResponse] is whatever came back, if anything, so the FAILED audit row can keep
 * it — the operator's first question after "it did not work" is "what did it say".
 */
class AiProviderException(
    message: String,
    cause: Throwable? = null,
    val rawResponse: String? = null,
    /**
     * What the vendor billed for a request that produced no answer — a refusal, a ceiling hit by
     * thinking alone. Null when there was no body to read it from. Charged like a success, or the
     * budget would have a hole exactly the shape of a retry loop.
     */
    val tokensIn: Int? = null,
    val tokensOut: Int? = null,
) : RuntimeException(message, cause)
