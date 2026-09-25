package core.ems.service.ai

import core.db.AiProviderType
import core.db.Course
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.boot.restclient.RestTemplateBuilder
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a course's stored AI config into a provider, or into nothing.
 *
 * Nothing is the normal case and not an error: a course with no key has no AI features, and every
 * caller has to treat null as "hide it", never as "fail the request". The `when` in [forCourse] is
 * the single place that grows when a second provider type arrives (EZ-1711).
 */
@Service
class AiProviderFactory(private val restTemplateBuilder: RestTemplateBuilder) {

    // One RestTemplate per base URL, kept for the life of the process: a client is connection
    // pooling and converters, and building one per click is the cold-handshake tax on every
    // student. The provider object itself is still per request — it carries the course's key.
    private val clients = ConcurrentHashMap<String, RestTemplate>()

    private fun clientFor(baseUrl: String?): RestTemplate =
        clients.computeIfAbsent(baseUrl ?: "") { AnthropicProvider.buildClient(restTemplateBuilder) }

    fun configForCourse(courseId: Long): AiProviderConfig? = transaction {
        Course.select(Course.aiProvider, Course.aiApiKey, Course.aiBaseUrl, Course.aiModel)
            .where { Course.id eq courseId }
            .firstOrNull()
            ?.let {
                val type = it[Course.aiProvider] ?: return@let null
                val key = it[Course.aiApiKey]?.takeIf { k -> k.isNotBlank() } ?: return@let null
                AiProviderConfig(
                    type = type,
                    apiKey = key,
                    baseUrl = it[Course.aiBaseUrl]?.takeIf { u -> u.isNotBlank() },
                    model = it[Course.aiModel]?.takeIf { m -> m.isNotBlank() } ?: DEFAULT_MODEL,
                )
            }
    }

    fun isConfigured(courseId: Long): Boolean = configForCourse(courseId) != null

    fun forCourse(courseId: Long): AiProvider? = configForCourse(courseId)?.let { create(it) }

    fun create(config: AiProviderConfig): AiProvider = when (config.type) {
        AiProviderType.ANTHROPIC -> AnthropicProvider(config, clientFor(config.baseUrl))
    }

    companion object {
        // Sonnet, not Opus: a five-sentence explanation of a failed test does not need the top
        // tier, and Sonnet is less than half the price per token. A teacher can type any model id.
        const val DEFAULT_MODEL = "claude-sonnet-5"
    }
}
