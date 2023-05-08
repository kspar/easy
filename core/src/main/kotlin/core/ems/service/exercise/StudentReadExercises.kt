package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.GradeResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.studentOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.toGradeRespOrNull
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

enum class StudentExerciseStatus { UNSTARTED, STARTED, COMPLETED, UNGRADED }

@RestController
@RequestMapping("/v2")
class StudentReadExercisesController {
    private val log = KotlinLogging.logger {}

    data class ExerciseResp(
        @JsonProperty("id") val courseExId: String,
        @JsonProperty("effective_title") val title: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("deadline") val softDeadline: DateTime?,
        @JsonProperty("status") val status: StudentExerciseStatus,
        @JsonProperty("grade") val grade: GradeResp?,
        @JsonProperty("ordering_idx") val orderingIndex: Int
    )

    data class Resp(@JsonProperty("exercises") val exercises: List<ExerciseResp>)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.debug { "Getting exercises for student ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        return selectStudentExercises(courseId, caller.id)
    }

    private fun selectStudentExercises(courseId: Long, studentId: String): Resp {

        data class ExercisePartial(
            val courseExId: Long,
            val title: String,
            val deadline: DateTime?,
            val threshold: Int,
            val titleAlias: String?
        )

        return transaction {
            Resp(
                (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                    .slice(
                        ExerciseVer.title,
                        CourseExercise.id,
                        CourseExercise.softDeadline,
                        CourseExercise.gradeThreshold,
                        CourseExercise.orderIdx,
                        CourseExercise.titleAlias
                    )
                    .select {
                        CourseExercise.course eq courseId and
                                ExerciseVer.validTo.isNull() and
                                CourseExercise.studentVisibleFrom.isNotNull() and
                                CourseExercise.studentVisibleFrom.lessEq(DateTime.now())
                    }
                    .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
                    .map {
                        ExercisePartial(
                            it[CourseExercise.id].value,
                            it[ExerciseVer.title],
                            it[CourseExercise.softDeadline],
                            it[CourseExercise.gradeThreshold],
                            it[CourseExercise.titleAlias]
                        )
                    }.mapIndexed { i, ex ->

                        val lastSub =
                            Submission
                                .slice(Submission.id, Submission.grade, Submission.isAutoGrade)
                                .select {
                                    Submission.courseExercise eq ex.courseExId and
                                            (Submission.student eq studentId)
                                }
                                .orderBy(Submission.createdAt, SortOrder.DESC)
                                .limit(1)
                                .map {
                                    val grade = it[Submission.grade]
                                    val isAuto = it[Submission.isAutoGrade]
                                    it[Submission.id].value to toGradeRespOrNull(grade, isAuto)
                                }.firstOrNull()


                        val submissionId = lastSub?.first
                        val grade = lastSub?.second?.grade

                        val status: StudentExerciseStatus =
                            when {
                                submissionId == null -> StudentExerciseStatus.UNSTARTED
                                grade == null -> StudentExerciseStatus.UNGRADED
                                grade >= ex.threshold -> StudentExerciseStatus.COMPLETED
                                else -> StudentExerciseStatus.STARTED
                            }

                        ExerciseResp(
                            ex.courseExId.toString(),
                            ex.titleAlias ?: ex.title,
                            ex.deadline,
                            status,
                            lastSub?.second,
                            i
                        )
                    }
            )
        }
    }
}

