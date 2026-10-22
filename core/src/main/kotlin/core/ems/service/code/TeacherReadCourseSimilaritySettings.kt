package core.ems.service.code

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.CourseExerciseSimilaritySettings
import core.db.CourseSimilaritySettings
import core.db.SimilarityMode
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.code.similarity.SimilarityService
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A course's similarity settings, with the providers a teacher may pin: those an admin has left on, each
 * with the languages it may compare. A course with none set answers the defaults - no provider pinned,
 * so the admin's preference order decides, and `optimised`. With them, the exercises that have settings
 * of their own, so the dialog can say which do not follow the course's.
 */
@RestController
@RequestMapping("/v2")
class TeacherReadCourseSimilaritySettingsController(private val similarityService: SimilarityService) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        /** The provider pinned, or null for the admin's preference order. */
        @get:JsonProperty("provider") val provider: String?,
        /** `full` or `optimised`. */
        @get:JsonProperty("mode") val mode: String,
        @get:JsonProperty("providers") val providers: List<RespProvider>,
        /** The course exercises with settings of their own, by course exercise id. */
        @get:JsonProperty("exercises") val exercises: List<RespExercise>,
    )

    data class RespExercise(
        @get:JsonProperty("course_exercise_id") val courseExerciseId: String,
        /** False: the course's provider. True: [provider], which null makes the admin's preference order. */
        @get:JsonProperty("own_provider") val ownProvider: Boolean,
        @get:JsonProperty("provider") val provider: String?,
        /** `full`, `optimised`, or null for the course's. */
        @get:JsonProperty("mode") val mode: String?,
    )

    data class RespProvider(
        @get:JsonProperty("id") val id: String,
        /** The languages it may compare now, in the order of their wire names. */
        @get:JsonProperty("languages") val languages: List<String>,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/similarity-settings")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {
        log.info { "Read similarity settings of course $courseIdStr by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }

        val (row, exercises) = transaction {
            CourseSimilaritySettings.selectAll().where { CourseSimilaritySettings.course eq courseId }.singleOrNull() to
                    (CourseExerciseSimilaritySettings innerJoin CourseExercise).selectAll()
                        .where { CourseExercise.course eq courseId }
                        .orderBy(CourseExerciseSimilaritySettings.courseExercise)
                        .map {
                            RespExercise(
                                it[CourseExerciseSimilaritySettings.courseExercise].value.toString(),
                                it[CourseExerciseSimilaritySettings.ownProvider],
                                it[CourseExerciseSimilaritySettings.provider],
                                it[CourseExerciseSimilaritySettings.mode]?.name?.lowercase(),
                            )
                        }
        }
        val registry = similarityService.registry
        val settings = registry.settings()
        return Resp(
            row?.get(CourseSimilaritySettings.provider),
            (row?.get(CourseSimilaritySettings.mode) ?: SimilarityMode.OPTIMISED).name.lowercase(),
            registry.preferenceOrder(settings).filter { settings.enabled(it) }.map { provider ->
                // What a check would let it compare: on for it, and not switched off as a whole - listed
                // otherwise, the dialog offered a provider for a language every check then refused.
                val languages = provider.languages.keys.filter { settings.languageEnabled(it) && settings.mayCompare(provider, it) }
                RespProvider(provider.id, languages.map { it.wireName }.sorted())
            },
            exercises,
        )
    }
}
