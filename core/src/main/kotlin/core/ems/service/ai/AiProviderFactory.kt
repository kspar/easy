package core.ems.service.ai

import core.db.AiProviderType
import core.db.Course
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

/**
 * Turns a course's stored AI config into a provider, or into nothing.
 *
 * Nothing is the normal case and not an error: a course with no key has no AI features, and every
 * caller has to treat null as "hide it", never as "fail the request". The `when` in [forCourse] is
 * the single place that grows when a second provider type arrives (EZ-1711).
 */
@Service
class AiProviderFactory {

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
        AiProviderType.ANTHROPIC -> AnthropicProvider(config)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}
