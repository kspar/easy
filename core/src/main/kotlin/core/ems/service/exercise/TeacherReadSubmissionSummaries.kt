package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
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
class TeacherReadSubmissionSummariesController {

    data class Resp(@JsonProperty("student_id") val studentId: String,
                    @JsonProperty("given_name") val studentGivenName: String,
                    @JsonProperty("family_name") val studentFamilyName: String,
                    @JsonSerialize(using = DateTimeSerializer::class)
                    @JsonProperty("submission_time") val submissionTime: DateTime?,
                    @JsonProperty("grade") val grade: Int?,
                    @JsonProperty("graded_by") val gradedBy: GraderType?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @PathVariable("courseExerciseId") courseExerciseIdString: String,
                   caller: EasyUser): List<Resp> {

        log.debug { "Getting submission summaries for ${caller.id} on course exercise $courseExerciseIdString on course $courseIdString" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return selectTeacherSubmissionSummaries(courseId, courseExerciseIdString.idToLongOrInvalidReq())
    }
}

private fun selectTeacherSubmissionSummaries(courseId: Long, courseExId: Long):
        List<TeacherReadSubmissionSummariesController.Resp> {
    return transaction {
        val maxSubTime = Submission.createdAt.max()

        (StudentCourseAccess innerJoin Student leftJoin (Submission innerJoin CourseExercise))
                .slice(Student.id, Student.givenName, Student.familyName, maxSubTime)
                .select {
                    // CourseExercise.id & CourseExercise.course are null when the student has no submission
                    (CourseExercise.id eq courseExId or CourseExercise.id.isNull()) and
                            (CourseExercise.course eq courseId or CourseExercise.course.isNull()) and
                            (StudentCourseAccess.course eq courseId)
                }
                .groupBy(Student.id, Student.givenName, Student.familyName)
                .map { latestSubmission ->

                    val latestSubTime = latestSubmission[maxSubTime]

                    if (latestSubTime == null) {
                        TeacherReadSubmissionSummariesController.Resp(
                                latestSubmission[Student.id].value,
                                latestSubmission[Student.givenName],
                                latestSubmission[Student.familyName],
                                null,
                                null,
                                null
                        )
                    } else {
                        var gradedBy: GraderType? = null
                        var grade = selectTeacherGrade(latestSubTime)

                        if (grade != null) {
                            gradedBy = GraderType.TEACHER
                        } else {
                            grade = selectAutoGrade(latestSubTime)
                            if (grade != null) {
                                gradedBy = GraderType.AUTO
                            }
                        }

                        TeacherReadSubmissionSummariesController.Resp(
                                latestSubmission[Student.id].value,
                                latestSubmission[Student.givenName],
                                latestSubmission[Student.familyName],
                                latestSubTime,
                                grade,
                                gradedBy
                        )
                    }
                }
    }
}

private fun selectAutoGrade(submissionTime: DateTime): Int? {
    return (AutomaticAssessment innerJoin Submission)
            .slice(AutomaticAssessment.grade)
            .select { Submission.createdAt eq submissionTime }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[AutomaticAssessment.grade] }
            .firstOrNull()
}

private fun selectTeacherGrade(submissionTime: DateTime): Int? {
    return (TeacherAssessment innerJoin Submission)
            .slice(TeacherAssessment.grade)
            .select { Submission.createdAt eq submissionTime }
            .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[TeacherAssessment.grade] }
            .firstOrNull()
}
