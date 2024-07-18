package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.aas.selectAutoExercise
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.util.DateTimeSerializer
import core.util.notNullAndInPast
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
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
        @JsonProperty("exercise_id") val exerciseId: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("title_alias") val titleAlias: String?,
        @JsonProperty("instructions_html") val instructionsHtml: String?,
        @JsonProperty("instructions_adoc") val instructionsAdoc: String?,
        @JsonProperty("text_html") val textHtml: String?,
        @JsonProperty("text_adoc") val textAdoc: String?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("soft_deadline") val softDeadline: DateTime?,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("hard_deadline") val hardDeadline: DateTime?,
        @JsonProperty("grader_type") val grader: GraderType,
        @JsonProperty("solution_file_name") val solutionFileName: String,
        @JsonProperty("solution_file_type") val solutionFileType: SolutionFileType,
        @JsonProperty("threshold") val threshold: Int,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("last_modified") val lastModified: DateTime,
        @JsonProperty("student_visible") val studentVisible: Boolean,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("student_visible_from") val studentVisibleFrom: DateTime?,
        @JsonProperty("assessments_student_visible") val assStudentVisible: Boolean,
        @JsonProperty("grading_script") val gradingScript: String?,
        @JsonProperty("container_image") val containerImage: String?,
        @JsonProperty("max_time_sec") val maxTime: Int?,
        @JsonProperty("max_mem_mb") val maxMem: Int?,
        @JsonProperty("assets") val assets: List<RespAsset>?,
        @JsonProperty("executors") val executors: List<RespExecutor>?,
        @JsonProperty("has_lib_access") val hasLibAccess: Boolean,
        @JsonProperty("exception_students") val exceptionStudents: List<RespExceptionStudent>?,
        @JsonProperty("exception_groups") val exceptionGroups: List<RespExceptionGroup>?,
    )

    data class RespAsset(
        @JsonProperty("file_name") val fileName: String,
        @JsonProperty("file_content") val fileContent: String
    )

    data class RespExecutor(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
    )

    data class ExceptionValueResp(@JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("value") val value: DateTime?)

    data class RespExceptionStudent(
        @JsonProperty("student_id") val studentId: String,
        @JsonProperty("soft_deadline") val softDeadline: ExceptionValueResp?,
        @JsonProperty("hard_deadline") val hardDeadline: ExceptionValueResp?,
        @JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueResp?,
    )

    data class RespExceptionGroup(
        @JsonProperty("group_id") val groupId: Long,
        @JsonProperty("soft_deadline") val softDeadline: ExceptionValueResp?,
        @JsonProperty("hard_deadline") val hardDeadline: ExceptionValueResp?,
        @JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueResp?,
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
        val exceptions = selectCourseExerciseExceptions(listOf(courseExId), emptyList())

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
