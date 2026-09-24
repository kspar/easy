package core.ems.service.ai

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.AiProviderType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

/**
 * The Anthropic Messages API over plain HTTP.
 *
 * Not the vendor's Java SDK. It bundles Jackson 2 and core is on Jackson 3; two Jacksons on one
 * classpath work, but the SDK would then be the only thing in the build that needs the second one,
 * and everything it would give us — retries, streaming, typed builders for tools — is on the
 * out-of-scope list for this feature. `RestTemplate` is what every other outbound call in core uses
 * (see YouTrackService), and the request is four fields.
 *
 * Nothing about thinking or effort is sent. The request stays valid for whichever model id a
 * teacher types in, and on the models this is meant for, thinking is on by default anyway — the
 * cost of that shows up in [AiCompletionRequest.maxTokens], not here.
 */
class AnthropicProvider(
    private val config: AiProviderConfig,
    builder: RestTemplateBuilder = RestTemplateBuilder(),
) : AiProvider {

    private val log = KotlinLogging.logger {}

    override val type = AiProviderType.ANTHROPIC
    override val model = config.model

    private val client = builder
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(READ_TIMEOUT)
        .build()

    private val messagesUrl = (config.baseUrl?.trimEnd('/') ?: DEFAULT_BASE_URL) + MESSAGES_PATH

    // Wire DTOs. `@JsonProperty` is what Jackson 3 reads for names (the annotations package did not
    // move), and the names are the API's, so this is the one place snake_case is right in Kotlin.
    private data class MessageReq(
        @get:JsonProperty("role") val role: String,
        @get:JsonProperty("content") val content: String,
    )

    private data class MessagesReq(
        @get:JsonProperty("model") val model: String,
        @get:JsonProperty("max_tokens") val maxTokens: Int,
        @get:JsonProperty("system") val system: String,
        @get:JsonProperty("messages") val messages: List<MessageReq>,
    )

    override fun complete(request: AiCompletionRequest): AiCompletionResult {
        val headers = HttpHeaders().apply {
            set("x-api-key", config.apiKey)
            set("anthropic-version", API_VERSION)
            contentType = MediaType.APPLICATION_JSON
        }
        val body = MessagesReq(
            config.model, request.maxTokens, request.system, listOf(MessageReq("user", request.user))
        )

        val raw = try {
            client.postForEntity(messagesUrl, HttpEntity(body, headers), String::class.java).body
                ?: throw AiProviderException("Anthropic returned an empty body")
        } catch (e: AiProviderException) {
            throw e
        } catch (e: RestClientResponseException) {
            // 401 (bad key), 429, 5xx. The body is the vendor's error JSON and says which.
            throw AiProviderException(
                "Anthropic answered ${e.statusCode.value()}", e, e.responseBodyAsString
            )
        } catch (e: ResourceAccessException) {
            throw AiProviderException("Anthropic unreachable or timed out: ${e.message}", e)
        } catch (e: Exception) {
            // A base URL RestTemplate cannot even start on — an odd scheme, a `{` it reads as a
            // template variable. Configuration, not the student's doing, and it still has to end
            // up as a FAILED row rather than a 500.
            throw AiProviderException("Anthropic request could not be made: ${e.message}", e)
        }

        return parse(raw)
    }

    private fun parse(raw: String): AiCompletionResult {
        val root: JsonNode = try {
            mapper.readTree(raw)
        } catch (e: Exception) {
            throw AiProviderException("Anthropic returned something that is not JSON", e, raw)
        }

        val stopReason = root.get("stop_reason")?.takeUnless { it.isNull }?.asString()
        // A refusal is HTTP 200 with no usable text. Treated the same as any other non-answer: the
        // student sees "unavailable", the FAILED row keeps the body that says why.
        if (stopReason == "refusal") {
            throw AiProviderException("Anthropic refused the request", rawResponse = raw)
        }
        if (stopReason == "max_tokens") {
            log.warn { "Anthropic hit max_tokens on model ${config.model}; the explanation may be cut short" }
        }

        val text = root.get("content")?.toList().orEmpty()
            .filter { it.get("type")?.asString() == "text" }
            .mapNotNull { it.get("text")?.asString() }
            .joinToString("")
            .trim()
        if (text.isEmpty()) {
            throw AiProviderException("Anthropic returned no text block (stop_reason: $stopReason)", rawResponse = raw)
        }

        val usage = root.get("usage")
        return AiCompletionResult(
            text = text,
            model = root.get("model")?.takeUnless { it.isNull }?.asString() ?: config.model,
            tokensIn = usage?.get("input_tokens")?.takeUnless { it.isNull }?.asInt(),
            tokensOut = usage?.get("output_tokens")?.takeUnless { it.isNull }?.asInt(),
            rawResponse = raw,
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"

        // Shared: a provider is built per request, and an ObjectMapper is the expensive part of it.
        private val mapper = jacksonObjectMapper()
        private const val MESSAGES_PATH = "/v1/messages"
        private const val API_VERSION = "2023-06-01"

        // The student is waiting on this. Sixty seconds is longer than a five-sentence answer takes
        // and shorter than anyone keeps a spinner company; a model that needs more is misconfigured.
        private val CONNECT_TIMEOUT = Duration.ofSeconds(10)
        private val READ_TIMEOUT = Duration.ofSeconds(60)
    }
}
