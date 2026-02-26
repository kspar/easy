package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.aas.selectAutoExercise
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.util.DateTimeSerializer
import core.util.notNullAndInPast
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
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
class TeacherReadExDetailsCont {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("exercise_id") val exerciseId: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("title_alias") val titleAlias: String?,
        @get:JsonProperty("instructions_html") val instructionsHtml: String?,
        @get:JsonProperty("instructions_adoc") val instructionsAdoc: String?,
        @get:JsonProperty("text_html") val textHtml: String?,
        @get:JsonProperty("text_adoc") val textAdoc: String?,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("soft_deadline") val softDeadline: DateTime?,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("hard_deadline") val hardDeadline: DateTime?,
        @get:JsonProperty("grader_type") val grader: GraderType,
        @get:JsonProperty("solution_file_name") val solutionFileName: String,
        @get:JsonProperty("solution_file_type") val solutionFileType: SolutionFileType,
        @get:JsonProperty("threshold") val threshold: Int,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_modified") val lastModified: DateTime,
        @get:JsonProperty("student_visible") val studentVisible: Boolean,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("student_visible_from") val studentVisibleFrom: DateTime?,
        @get:JsonProperty("assessments_student_visible") val assStudentVisible: Boolean,
        @get:JsonProperty("grading_script") val gradingScript: String?,
        @get:JsonProperty("container_image") val containerImage: String?,
        @get:JsonProperty("max_time_sec") val maxTime: Int?,
        @get:JsonProperty("max_mem_mb") val maxMem: Int?,
        @get:JsonProperty("assets") val assets: List<RespAsset>?,
        @get:JsonProperty("executors") val executors: List<RespExecutor>?,
        @get:JsonProperty("has_lib_access") val hasLibAccess: Boolean,
        @get:JsonProperty("exception_students") val exceptionStudents: List<RespExceptionStudent>?,
        @get:JsonProperty("exception_groups") val exceptionGroups: List<RespExceptionGroup>?,
    )

    data class RespAsset(
        @get:JsonProperty("file_name") val fileName: String,
        @get:JsonProperty("file_content") val fileContent: String
    )

    data class RespExecutor(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String
    )

    data class ExceptionValueResp(@get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("value") val value: DateTime?)

    data class RespExceptionStudent(
        @get:JsonProperty("student_id") val studentId: String,
        @get:JsonProperty("soft_deadline") val softDeadline: ExceptionValueResp?,
        @get:JsonProperty("hard_deadline") val hardDeadline: ExceptionValueResp?,
        @get:JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueResp?,
    )

    data class RespExceptionGroup(
        @get:JsonProperty("group_id") val groupId: Long,
        @get:JsonProperty("soft_deadline") val softDeadline: ExceptionValueResp?,
        @get:JsonProperty("hard_deadline") val hardDeadline: ExceptionValueResp?,
        @get:JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueResp?,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        caller: EasyUser
    ): Resp {

        log.info { "Getting exercise details for ${caller.id} for course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }

        return selectCourseExerciseDetails(courseId, courseExerciseIdString.idToLongOrInvalidReq(), caller)
    }

    private fun selectCourseExerciseDetails(courseId: Long, courseExId: Long, caller: EasyUser): Resp = transaction {
        val exceptions = selectCourseExerciseExceptions(courseExId)

        val exceptionStudents = exceptions.studentExceptions[courseExId]?.map {
            RespExceptionStudent(
                it.studentId,
                if (it.softDeadline != null) ExceptionValueResp(it.softDeadline.value) else null,
                if (it.hardDeadline != null) ExceptionValueResp(it.hardDeadline.value) else null,
                if (it.studentVisibleFrom != null) ExceptionValueResp(it.studentVisibleFrom.value) else null,
            )
        }

        val exceptionGroups = exceptions.groupExceptions[courseExId]?.map {
            RespExceptionGroup(
                it.courseGroup,
                if (it.softDeadline != null) ExceptionValueResp(it.softDeadline.value) else null,
                if (it.hardDeadline != null) ExceptionValueResp(it.hardDeadline.value) else null,
                if (it.studentVisibleFrom != null) ExceptionValueResp(it.studentVisibleFrom.value) else null,
            )
        }

        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(
                Exercise.id,
                CourseExercise.softDeadline,
                CourseExercise.hardDeadline,
                CourseExercise.gradeThreshold,
                CourseExercise.studentVisibleFrom,
                CourseExercise.assessmentsStudentVisible,
                CourseExercise.instructionsHtml,
                CourseExercise.instructionsAdoc,
                CourseExercise.titleAlias,
                ExerciseVer.title,
                ExerciseVer.textHtml,
                ExerciseVer.textAdoc,
                ExerciseVer.graderType,
                ExerciseVer.validFrom,
                ExerciseVer.autoExerciseId,
                ExerciseVer.solutionFileName,
                ExerciseVer.solutionFileType
            )
            .where {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        ExerciseVer.validTo.isNull()
            }
            .map {
                val graderType = it[ExerciseVer.graderType]

                val autoExercise =
                    if (graderType == GraderType.AUTO) {
                        val autoExerciseId = it[ExerciseVer.autoExerciseId]
                            ?: throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
                        selectAutoExercise(autoExerciseId)
                    } else null

                val exerciseId = it[Exercise.id].value
                val hasLibAccess =
                    hasAccountDirAccess(caller, getImplicitDirFromExercise(exerciseId), DirAccessLevel.PR)

                Resp(
                    exerciseId.toString(),
                    it[ExerciseVer.title],
                    it[CourseExercise.titleAlias],
                    it[CourseExercise.instructionsHtml],
                    it[CourseExercise.instructionsAdoc],
                    it[ExerciseVer.textHtml],
                    it[ExerciseVer.textAdoc],
                    it[CourseExercise.softDeadline],
                    it[CourseExercise.hardDeadline],
                    it[ExerciseVer.graderType],
                    it[ExerciseVer.solutionFileName],
                    it[ExerciseVer.solutionFileType],
                    it[CourseExercise.gradeThreshold],
                    it[ExerciseVer.validFrom],
                    it[CourseExercise.studentVisibleFrom].notNullAndInPast(),
                    it[CourseExercise.studentVisibleFrom],
                    it[CourseExercise.assessmentsStudentVisible],
                    autoExercise?.gradingScript,
                    autoExercise?.containerImage,
                    autoExercise?.maxTime,
                    autoExercise?.maxMem,
                    autoExercise?.assets?.map { RespAsset(it.first, it.second) },
                    autoExercise?.executors?.map {
                        RespExecutor(
                            it.id.toString(),
                            it.name
                        )
                    },
                    hasLibAccess,
                    exceptionStudents,
                    exceptionGroups
                )
            }
            .singleOrInvalidRequest()
    }
}
