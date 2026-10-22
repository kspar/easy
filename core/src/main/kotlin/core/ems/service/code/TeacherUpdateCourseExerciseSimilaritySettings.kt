package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExerciseSimilaritySettings
import core.db.SimilarityAttempts
import core.db.SimilarityMode
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Sets one course exercise's own similarity settings, over its course's (EZ-1667): an exam comparing every
 * pair while the weekly exercises compare the likeliest first, or one exercise on the provider that reads
 * its language best. Each setting is its own - the provider can be the exercise's while the mode stays the
 * course's. A provider is checked as the course's is: pinned only while an admin has it on.
 *
 * Neither its own is no setting at all, and is stored as none - the row is deleted, never the exercise - so
 * the exercise follows the course's as one that was never set does. That is also the one way to clear an
 * exercise's settings: a DELETE beside it would only have been a second way to do the same.
 */
@RestController
@RequestMapping("/v2")
class TeacherUpdateCourseExerciseSimilaritySettingsController(private val similarityService: SimilarityService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        /** False takes the course's provider, and [provider] is ignored; true takes [provider]. */
        @param:JsonProperty("own_provider", required = true) val ownProvider: Boolean,
        /** A provider to pin, or null for the admin's preference order even where the course pins one. */
        @param:JsonProperty("provider") @field:Size(max = 50) val provider: String?,
        /** `full`, `optimised`, or null for the course's. */
        @param:JsonProperty("mode") @field:Size(max = 20) val mode: String?,
        /** `latest`, `first`, or null for the course's. */
        @param:JsonProperty("attempts") @field:Size(max = 20) val attempts: String? = null,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/similarity-settings/exercises/{courseExerciseId}")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExerciseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser,
    ) {
        log.info { "Update similarity settings of course exercise $courseExerciseIdStr on course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExerciseId = courseExerciseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExerciseId, courseId)

        val mode = body.mode?.let { wire ->
            SimilarityMode.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
                ?: throw InvalidRequestException("Unknown similarity mode.", ReqError.INVALID_PARAMETER_VALUE, "mode" to wire, notify = false)
        }
        val attempts = body.attempts?.let { wire ->
            SimilarityAttempts.entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
                ?: throw InvalidRequestException("Unknown attempts.", ReqError.INVALID_PARAMETER_VALUE, "attempts" to wire, notify = false)
        }
        val provider = body.provider.takeIf { body.ownProvider }
        if (provider != null) {
            val registry = similarityService.registry
            val installed = registry.provider(provider)
            if (installed == null || !registry.settings().enabled(installed)) throw InvalidRequestException(
                "Similarity provider '$provider' is not available.",
                ReqError.INVALID_PARAMETER_VALUE, "provider" to provider, notify = false,
            )
        }

        transaction {
            if (!body.ownProvider && mode == null && attempts == null) {
                CourseExerciseSimilaritySettings.deleteWhere { CourseExerciseSimilaritySettings.courseExercise eq courseExerciseId }
            } else {
                CourseExerciseSimilaritySettings.upsert {
                    it[CourseExerciseSimilaritySettings.courseExercise] = courseExerciseId
                    it[ownProvider] = body.ownProvider
                    it[CourseExerciseSimilaritySettings.provider] = provider
                    it[CourseExerciseSimilaritySettings.mode] = mode
                    it[CourseExerciseSimilaritySettings.attempts] = attempts
                }
            }
        }
    }
}
