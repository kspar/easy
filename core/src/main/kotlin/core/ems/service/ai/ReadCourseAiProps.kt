package core.ems.service.ai

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.AiProviderType
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * EZ-1711. What a course has configured, minus the one thing that matters: the key never comes
 * back, only whether one is there and its last four characters, which is enough for a teacher to
 * tell their keys apart and useless to anyone else.
 */
@RestController
@RequestMapping("/v2")
class ReadCourseAiPropsController {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("ai_props") val aiProps: AiPropsResp?)

    data class AiPropsResp(
        @get:JsonProperty("provider") val provider: AiProviderType,
        @get:JsonProperty("model") val model: String,
        @get:JsonProperty("base_url") val baseUrl: String?,
        @get:JsonProperty("api_key_configured") val apiKeyConfigured: Boolean,
        @get:JsonProperty("api_key_hint") val apiKeyHint: String?,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/ai")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {
        log.info { "Getting AI props for course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }
        return Resp(selectAiProps(courseId))
    }

    private fun selectAiProps(courseId: Long): AiPropsResp? = transaction {
        Course.select(Course.aiProvider, Course.aiApiKey, Course.aiBaseUrl, Course.aiModel)
            .where { Course.id eq courseId }
            .single()
            .let {
                val provider = it[Course.aiProvider] ?: return@let null
                val key = it[Course.aiApiKey]?.takeIf { k -> k.isNotBlank() }
                AiPropsResp(
                    provider = provider,
                    model = it[Course.aiModel]?.takeIf { m -> m.isNotBlank() } ?: AiProviderFactory.DEFAULT_MODEL,
                    baseUrl = it[Course.aiBaseUrl]?.takeIf { u -> u.isNotBlank() },
                    apiKeyConfigured = key != null,
                    apiKeyHint = key?.takeLast(KEY_HINT_LENGTH),
                )
            }
    }

    companion object {
        const val KEY_HINT_LENGTH = 4
    }
}
