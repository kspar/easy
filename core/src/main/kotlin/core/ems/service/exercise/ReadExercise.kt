package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.aas.selectAutoExercise
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryExercise
import core.ems.service.getAccountDirAccessLevel
import core.ems.service.getImplicitDirFromExercise
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.singleOrInvalidRequest
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadExercise {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("dir_id") val implicitDirId: String,
        @get:JsonProperty("effective_access") val effectiveAccess: DirAccessLevel,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val created_at: DateTime,
        @get:JsonProperty("is_public") val public: Boolean,
        @get:JsonProperty("is_anonymous_autoassess_enabled") val anonymousAutoassessEnabled: Boolean,
        @get:JsonProperty("owner_id") val ownerUsername: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_modified") val lastModified: DateTime,
        @get:JsonProperty("last_modified_by_id") val lastModifiedBy: String,
        @get:JsonProperty("grader_type") val grader: GraderType,
        @get:JsonProperty("solution_file_name") val solutionFileName: String,
        @get:JsonProperty("solution_file_type") val solutionFileType: SolutionFileType,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("text_html") val textHtml: String?,
        @get:JsonProperty("text_adoc") val textAdoc: String?,
        @get:JsonProperty("anonymous_autoassess_template") val anonymousAutoassessTemplate: String?,
        @get:JsonProperty("grading_script") val gradingScript: String?,
        @get:JsonProperty("container_image") val containerImage: String?,
        @get:JsonProperty("max_time_sec") val maxTime: Int?,
        @get:JsonProperty("max_mem_mb") val maxMem: Int?,
        @get:JsonProperty("assets") val assets: List<RespAsset>?,
        @get:JsonProperty("executors") val executors: List<RespExecutor>?,
        @get:JsonProperty("on_courses") val courses: List<RespCourse>,
        @get:JsonProperty("on_courses_no_access") val coursesNoAccessCount: Int
    )

    data class RespAsset(
        @get:JsonProperty("file_name") val fileName: String,
        @get:JsonProperty("file_content") val fileContent: String
    )

    data class RespExecutor(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String
    )

    data class RespCourse(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("alias") val alias: String?,
        @get:JsonProperty("course_exercise_id") val courseExId: String,
        @get:JsonProperty("course_exercise_title_alias") val titleAlias: String?
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/exercises/{exerciseId}")
    fun controller(@PathVariable("exerciseId") exIdString: String, caller: EasyUser): Resp {

        log.info { "Read exercise $exIdString by ${caller.id}" }
        val exerciseId = exIdString.idToLongOrInvalidReq()

        caller.assertAccess { libraryExercise(exerciseId, DirAccessLevel.PR) }

        return selectExerciseDetails(exerciseId, caller)
    }

    private fun selectExerciseDetails(exerciseId: Long, caller: EasyUser): Resp = transaction {
        data class UsedOnCourse(
            val id: String, val title: String, val courseAlias: String?,
            val courseExId: String, val exAlias: String?,
            val callerHasAccess: Boolean,
        )

        val usedOnCourses =
            (CourseExercise innerJoin Course).leftJoin(
                TeacherCourseAccess,
                onColumn = { Course.id }, otherColumn = { TeacherCourseAccess.course },
                additionalConstraint = { TeacherCourseAccess.teacher eq caller.id }).select(
                Course.id, Course.title, Course.alias, CourseExercise.id, CourseExercise.titleAlias,
                TeacherCourseAccess.teacher
            ).where {
                CourseExercise.exercise eq exerciseId
            }.map {
                @Suppress("SENSELESS_COMPARISON") // leftJoin
                UsedOnCourse(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    it[CourseExercise.id].value.toString(),
                    it[CourseExercise.titleAlias],
                    it[TeacherCourseAccess.teacher] != null,
                )
            }

        val (onCoursesAccess, onCoursesNoAccess) =
            usedOnCourses.partition { it.callerHasAccess || caller.isAdmin() }

        val dirId = getImplicitDirFromExercise(exerciseId)
        val access = getAccountDirAccessLevel(caller, dirId)
            ?: throw IllegalStateException("No access for ${caller.id} to dir $dirId")

        (Exercise innerJoin ExerciseVer)
            .select(
                Exercise.createdAt,
                Exercise.public,
                Exercise.owner,
                ExerciseVer.validFrom,
                ExerciseVer.author,
                ExerciseVer.graderType,
                ExerciseVer.solutionFileName,
                ExerciseVer.solutionFileType,
                ExerciseVer.title,
                ExerciseVer.textHtml,
                ExerciseVer.textAdoc,
                ExerciseVer.autoExerciseId,
                Exercise.anonymousAutoassessEnabled,
                Exercise.anonymousAutoassessTemplate
            )
            .where {
                Exercise.id eq exerciseId and
                        ExerciseVer.validTo.isNull()
            }.map {
                val graderType = it[ExerciseVer.graderType]
                val autoExercise =
                    if (graderType == GraderType.AUTO) {
                        val autoExerciseId = it[ExerciseVer.autoExerciseId]
                            ?: throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
                        selectAutoExercise(autoExerciseId)
                    } else null

                Resp(
                    dirId.toString(),
                    access,
                    it[Exercise.createdAt],
                    it[Exercise.public],
                    it[Exercise.anonymousAutoassessEnabled],
                    it[Exercise.owner].value,
                    it[ExerciseVer.validFrom],
                    it[ExerciseVer.author].value,
                    it[ExerciseVer.graderType],
                    it[ExerciseVer.solutionFileName],
                    it[ExerciseVer.solutionFileType],
                    it[ExerciseVer.title],
                    it[ExerciseVer.textHtml],
                    it[ExerciseVer.textAdoc],
                    it[Exercise.anonymousAutoassessTemplate],
                    autoExercise?.gradingScript,
                    autoExercise?.containerImage,
                    autoExercise?.maxTime,
                    autoExercise?.maxMem,
                    autoExercise?.assets?.map { RespAsset(it.first, it.second) },
                    autoExercise?.executors?.map { RespExecutor(it.id.toString(), it.name) },
                    onCoursesAccess.map { RespCourse(it.id, it.title, it.courseAlias, it.courseExId, it.exAlias) },
                    onCoursesNoAccess.count()
                )
            }.singleOrInvalidRequest()
    }
}
