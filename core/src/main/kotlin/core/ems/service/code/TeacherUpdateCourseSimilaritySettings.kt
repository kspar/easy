package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseSimilaritySettings
import core.db.SimilarityAttempts
import core.db.SimilarityMode
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sets a course's similarity settings. A provider may be pinned only while an admin has it on; switched
 * off later, the pin stays and is passed over (the check says so) until it is on again.
 */
@RestController
@RequestMapping("/v2")
class TeacherUpdateCourseSimilaritySettingsController(private val similarityService: SimilarityService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        /** A provider to pin, or null for the admin's preference order. */
        @param:JsonProperty("provider") @field:Size(max = 50) val provider: String?,
        /** `full` or `optimised`. */
        @param:JsonProperty("mode", required = true) @field:NotBlank @field:Size(max = 20) val mode: String,
        /**
         * `latest` or `first`. Optional, and left as it is when not given, so a page from before it existed
         * does not set every course it saves back to the latest.
         */
        @param:JsonProperty("attempts") @field:Size(max = 20) val attempts: String? = null,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/similarity-settings")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody body: Req, caller: EasyUser) {
        log.info { "Update similarity settings of course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }

        val mode = SimilarityMode.entries.firstOrNull { it.name.equals(body.mode, ignoreCase = true) }
            ?: throw InvalidRequestException("Unknown similarity mode.", ReqError.INVALID_PARAMETER_VALUE, "mode" to body.mode, notify = false)
        val attempts = body.attempts?.let { wire ->
            SimilarityAttempts.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
                ?: throw InvalidRequestException("Unknown attempts.", ReqError.INVALID_PARAMETER_VALUE, "attempts" to wire, notify = false)
        }
        if (body.provider != null) {
            val registry = similarityService.registry
            val provider = registry.provider(body.provider)
            if (provider == null || !registry.settings().enabled(provider)) throw InvalidRequestException(
                "Similarity provider '${body.provider}' is not available.",
                ReqError.INVALID_PARAMETER_VALUE, "provider" to body.provider, notify = false,
            )
        }

        transaction {
            val stored = CourseSimilaritySettings.selectAll().where { CourseSimilaritySettings.course eq courseId }
                .singleOrNull()?.get(CourseSimilaritySettings.attempts)
            CourseSimilaritySettings.upsert {
                it[course] = courseId
                it[provider] = body.provider
                it[CourseSimilaritySettings.mode] = mode
                it[CourseSimilaritySettings.attempts] = attempts ?: stored ?: SimilarityAttempts.LATEST
            }
        }
    }
}
