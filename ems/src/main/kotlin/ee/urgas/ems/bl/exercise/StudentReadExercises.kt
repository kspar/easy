package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.canStudentAccessCourse
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.AutomaticAssessment
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.db.GraderType
import ee.urgas.ems.db.Submission
import ee.urgas.ems.db.TeacherAssessment
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.util.DateTimeSerializer
import mu.KotlinLogging
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
@RequestMapping("/v1")
class StudentReadExercisesController {

    data class StudentExercisesResponse(@JsonProperty("id") val courseExId: String,
                                        @JsonProperty("title") val title: String,
                                        @JsonSerialize(using = DateTimeSerializer::class)
                                        @JsonProperty("deadline") val softDeadline: DateTime?,
                                        @JsonProperty("status") val status: StudentExerciseStatus,
                                        @JsonProperty("grade") val grade: Int?,
                                        @JsonProperty("graded_by") val gradedBy: GraderType?)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises")
    fun getStudentExercises(@PathVariable("courseId") courseIdStr: String, caller: EasyUser):
            List<StudentExercisesResponse> {

        val callerEmail = caller.email
        val courseId = courseIdStr.toLong()

        if (!canStudentAccessCourse(callerEmail, courseId)) {
            throw ForbiddenException("Student $callerEmail does not have access to course $courseId")
        }

        return selectStudentExercises(courseId, callerEmail)
    }
}


enum class StudentExerciseStatus { UNSTARTED, STARTED, COMPLETED }

private fun selectStudentExercises(courseId: Long, studentEmail: String):
        List<StudentReadExercisesController.StudentExercisesResponse> {

    data class ExercisePartial(val courseExId: Long, val title: String, val deadline: DateTime?, val threshold: Int)
    data class SubmissionPartial(val id: Long, val solution: String, val createdAt: DateTime)

    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.title, CourseExercise.id, CourseExercise.softDeadline, CourseExercise.gradeThreshold)
                .select {
                    CourseExercise.course eq courseId and
                            ExerciseVer.validTo.isNull() and
                            (CourseExercise.studentVisible eq true)
                }
                .map {
                    ExercisePartial(
                            it[CourseExercise.id].value,
                            it[ExerciseVer.title],
                            it[CourseExercise.softDeadline],
                            it[CourseExercise.gradeThreshold]
                    )
                }.map { ex ->

                    val lastSub =
                            Submission
                                    .select {
                                        Submission.courseExercise eq ex.courseExId and
                                                (Submission.student eq studentEmail)
                                    }
                                    .orderBy(Submission.createdAt to false)
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

                    StudentReadExercisesController.StudentExercisesResponse(
                            ex.courseExId.toString(),
                            ex.title,
                            ex.deadline,
                            status,
                            grade,
                            gradedBy
                    )
                }
    }
}

private fun lastAutoGrade(submissionId: Long): Int? {
    return AutomaticAssessment.select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to false)
            .limit(1)
            .map { it[AutomaticAssessment.grade] }
            .firstOrNull()
}

private fun lastTeacherGrade(submissionId: Long): Int? {
    return TeacherAssessment.select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to false)
            .limit(1)
            .map { it[TeacherAssessment.grade] }
            .firstOrNull()
}
