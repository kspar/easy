package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.studentOnCourse
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
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
class StudentReadExercisesController {
    private val log = KotlinLogging.logger {}

    data class ExerciseResp(
        @JsonProperty("id") val courseExId: String,
        @JsonProperty("effective_title") val title: String,
        @JsonProperty("grader_type") val graderType: GraderType,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("deadline") val softDeadline: DateTime?,
        @JsonProperty("is_open") val isOpenForSubmissions: Boolean,
        @JsonProperty("status") val status: StudentExerciseStatus,
        @JsonProperty("grade") val grade: GradeResp?,
        @JsonProperty("ordering_idx") val orderingIndex: Int
    )

    data class Resp(@JsonProperty("exercises") val exercises: List<ExerciseResp>)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.info { "Getting exercises for student ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        return selectStudentExercises(courseId, caller.id)
    }

    private fun selectStudentExercises(courseId: Long, studentId: String): Resp = transaction {
        val cexIds = CourseExercise.select(CourseExercise.id)
            .where { CourseExercise.course eq courseId }
            .map { it[CourseExercise.id].value }

        val cexToExceptions = selectCourseExerciseExceptions(cexIds, studentId)

        data class ExercisePartial(
            val courseExId: Long,
            val title: String,
            val graderType: GraderType,
            val deadline: DateTime?,
            val isOpen: Boolean,
            val threshold: Int,
            val titleAlias: String?
        )

        val exercisePartials = (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(
                ExerciseVer.title,
                ExerciseVer.graderType,
                CourseExercise.id,
                CourseExercise.softDeadline,
                CourseExercise.hardDeadline,
                CourseExercise.gradeThreshold,
                CourseExercise.orderIdx,
                CourseExercise.titleAlias,
                CourseExercise.studentVisibleFrom
            )
            .where {
                CourseExercise.course eq courseId and ExerciseVer.validTo.isNull()
            }
            .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
            .mapNotNull {
                val courseExId = it[CourseExercise.id].value

                val visibleFrom = determineCourseExerciseVisibleFrom(
                    cexToExceptions,
                    courseExId,
                    studentId,
                    it[CourseExercise.studentVisibleFrom]
                )

                if (visibleFrom == null || visibleFrom.isAfterNow) {
                    null
                } else {
                    ExercisePartial(
                        it[CourseExercise.id].value,
                        it[ExerciseVer.title],
                        it[ExerciseVer.graderType],
                        determineSoftDeadline(
                            cexToExceptions,
                            courseExId,
                            studentId,
                            it[CourseExercise.softDeadline]
                        ),
                        isCourseExerciseOpenForSubmit(
                            cexToExceptions,
                            courseExId,
                            studentId,
                            it[CourseExercise.hardDeadline]
                        ),
                        it[CourseExercise.gradeThreshold],
                        it[CourseExercise.titleAlias]
                    )
                }
            }

        data class SubmissionPartial(
            val courseExId: Long,
            val gradeResp: GradeResp?,
            val createdAt: DateTime
        )

        val submissions: Map<Long, SubmissionPartial> =
            Submission
                .select(
                    Submission.courseExercise,
                    Submission.grade,
                    Submission.isAutoGrade,
                    Submission.createdAt,
                    Submission.isGradedDirectly
                )
                .where { Submission.courseExercise inList (exercisePartials.map { it.courseExId }) and (Submission.student eq studentId) }
                .map {
                    SubmissionPartial(
                        it[Submission.courseExercise].value,
                        toGradeRespOrNull(
                            it[Submission.grade],
                            it[Submission.isAutoGrade],
                            it[Submission.isGradedDirectly]
                        ),
                        it[Submission.createdAt]
                    )
                }
                .groupBy { it.courseExId }
                .mapValues { exToSubEntry -> exToSubEntry.value.maxBy { subPartial -> subPartial.createdAt } }

        Resp(
            exercisePartials.mapIndexed { i, ex ->
                val lastSub: SubmissionPartial? = submissions[ex.courseExId]
                val grade = lastSub?.gradeResp?.grade
                val status = getStudentExerciseStatus(lastSub != null, grade, ex.threshold)

                ExerciseResp(
                    ex.courseExId.toString(),
                    ex.titleAlias ?: ex.title,
                    ex.graderType,
                    ex.deadline,
                    ex.isOpen,
                    status,
                    lastSub?.gradeResp,
                    i
                )
            }
        )
    }
}

fun getStudentExerciseStatus(hasSubmission: Boolean, grade: Int?, threshold: Int) = when {
    !hasSubmission -> StudentExerciseStatus.UNSTARTED
    grade == null -> StudentExerciseStatus.UNGRADED
    grade >= threshold -> StudentExerciseStatus.COMPLETED
    else -> StudentExerciseStatus.STARTED
}

