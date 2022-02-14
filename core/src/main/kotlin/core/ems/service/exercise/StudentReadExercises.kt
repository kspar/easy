package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
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

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentReadExercisesController {

    data class ExerciseResp(@JsonProperty("id") val courseExId: String,
                            @JsonProperty("effective_title") val title: String,
                            @JsonSerialize(using = DateTimeSerializer::class)
                            @JsonProperty("deadline") val softDeadline: DateTime?,
                            @JsonProperty("status") val status: StudentExerciseStatus,
                            @JsonProperty("grade") val grade: Int?,
                            @JsonProperty("graded_by") val gradedBy: GraderType?,
                            @JsonProperty("ordering_idx") val orderingIndex: Int)

    data class Resp(@JsonProperty("exercises") val exercises: List<ExerciseResp>)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.debug { "Getting exercises for student ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        return selectStudentExercises(courseId, caller.id)
    }
}


enum class StudentExerciseStatus { UNSTARTED, STARTED, COMPLETED }

private fun selectStudentExercises(courseId: Long, studentId: String): StudentReadExercisesController.Resp {

    data class ExercisePartial(val courseExId: Long, val title: String, val deadline: DateTime?, val threshold: Int,
                               val titleAlias: String?)

    data class SubmissionPartial(val id: Long, val solution: String, val createdAt: DateTime)

    return transaction {
        StudentReadExercisesController.Resp(
                (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                        .slice(ExerciseVer.title, CourseExercise.id, CourseExercise.softDeadline, CourseExercise.gradeThreshold,
                                CourseExercise.orderIdx, CourseExercise.titleAlias)
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
                                            .select {
                                                Submission.courseExercise eq ex.courseExId and
                                                        (Submission.student eq studentId)
                                            }
                                            .orderBy(Submission.createdAt, SortOrder.DESC)
                                            .limit(1)
                                            .map {
                                                SubmissionPartial(
                                                        it[Submission.id].value,
                                                        it[Submission.solution],
                                                        it[Submission.createdAt]
                                                )
                                            }
                                            .firstOrNull()

                            var gradedBy: GraderType? = null
                            var grade: Int? = null

                            if (lastSub != null) {
                                grade = lastTeacherGrade(lastSub.id)
                                if (grade != null) {
                                    gradedBy = GraderType.TEACHER
                                } else {
                                    grade = lastAutoGrade(lastSub.id)
                                    if (grade != null) {
                                        gradedBy = GraderType.AUTO
                                    }
                                }
                            }

                            val status: StudentExerciseStatus =
                                    if (lastSub == null) {
                                        StudentExerciseStatus.UNSTARTED
                                    } else if (grade != null && grade >= ex.threshold) {
                                        StudentExerciseStatus.COMPLETED
                                    } else {
                                        StudentExerciseStatus.STARTED
                                    }


                            StudentReadExercisesController.ExerciseResp(
                                    ex.courseExId.toString(),
                                    ex.titleAlias ?: ex.title,
                                    ex.deadline,
                                    status,
                                    grade,
                                    gradedBy,
                                    i
                            )
                        }
        )
    }
}

private fun lastAutoGrade(submissionId: Long): Int? {
    return AutomaticAssessment.select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[AutomaticAssessment.grade] }
            .firstOrNull()
}

private fun lastTeacherGrade(submissionId: Long): Int? {
    return TeacherAssessment.select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[TeacherAssessment.grade] }
            .firstOrNull()
}
