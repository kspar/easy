package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.assertTeacherOrAdminHasAccessToCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.*
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
@RequestMapping("/v2")
class TeacherReadCourseExercisesController {

    data class TeacherCourseExResp(@JsonProperty("id") val id: String,
                                   @JsonProperty("title") val title: String,
                                   @JsonSerialize(using = DateTimeSerializer::class)
                                   @JsonProperty("soft_deadline") val softDeadline: DateTime?,
                                   @JsonProperty("grader_type") val graderType: GraderType,
                                   @JsonProperty("ordering_idx") val orderingIndex: Int,
                                   @JsonProperty("unstarted_count") val unstartedCount: Int,
                                   @JsonProperty("ungraded_count") val ungradedCount: Int,
                                   @JsonProperty("started_count") val startedCount: Int,
                                   @JsonProperty("completed_count") val completedCount: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun readTeacherCourseExercises(@PathVariable("courseId") courseIdString: String, caller: EasyUser):
            List<TeacherCourseExResp> {

        log.debug { "Getting exercises on course $courseIdString for teacher/admin ${caller.id}" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return selectTeacherExercisesOnCourse(courseId)
    }
}


private fun selectTeacherExercisesOnCourse(courseId: Long):
        List<TeacherReadCourseExercisesController.TeacherCourseExResp> {

    return transaction {

        val studentCount = StudentCourseAccess.select {
            StudentCourseAccess.course eq courseId
        }.count()

        data class SubmissionPartial(val id: Long, val studentId: String, val createdAt: DateTime)

        (Course innerJoin CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.id, CourseExercise.gradeThreshold, CourseExercise.softDeadline,
                        CourseExercise.orderIdx,
                        Course.id, ExerciseVer.graderType, ExerciseVer.title, ExerciseVer.validTo)
                .select { Course.id eq courseId and ExerciseVer.validTo.isNull() }
                .orderBy(CourseExercise.orderIdx to true)
                .map { ex ->
                    // student_id -> submission
                    val lastSubmissions = HashMap<String, SubmissionPartial>()
                    Submission
                            .slice(Submission.id, Submission.student, Submission.createdAt, Submission.courseExercise)
                            .select { Submission.courseExercise eq ex[CourseExercise.id] }
                            .map {
                                SubmissionPartial(
                                        it[Submission.id].value,
                                        it[Submission.student].value,
                                        it[Submission.createdAt]
                                )
                            }
                            .forEach {
                                val lastSub = lastSubmissions.get(it.studentId)
                                if (lastSub == null || lastSub.createdAt.isBefore(it.createdAt)) {
                                    lastSubmissions[it.studentId] = it
                                }
                            }

                    val lastSubmissionIds = lastSubmissions.values.map { it.id }
                    val latestGrades = lastSubmissionIds.map { selectLatestGradeForSubmission(it) }
                    val gradeThreshold = ex[CourseExercise.gradeThreshold]

                    val unstartedCount = studentCount - lastSubmissionIds.size
                    val ungradedCount = latestGrades.count { it == null }
                    val startedCount = latestGrades.count { it != null && it < gradeThreshold }
                    val completedCount = latestGrades.count { it != null && it >= gradeThreshold }

                    // Sanity check
                    if (unstartedCount + ungradedCount + startedCount + completedCount != studentCount)
                        log.warn {
                            "Student grade sanity check failed. unstarted: $unstartedCount, ungraded: $ungradedCount, " +
                                    "started: $startedCount, completed: $completedCount, students in course: $studentCount"
                        }

                    TeacherReadCourseExercisesController.TeacherCourseExResp(
                            ex[CourseExercise.id].value.toString(),
                            ex[ExerciseVer.title],
                            ex[CourseExercise.softDeadline],
                            ex[ExerciseVer.graderType],
                            ex[CourseExercise.orderIdx],
                            unstartedCount,
                            ungradedCount,
                            startedCount,
                            completedCount
                    )

                }
    }
}

private fun selectLatestGradeForSubmission(submissionId: Long): Int? {
    val teacherGrade = TeacherAssessment
            .slice(TeacherAssessment.submission,
                    TeacherAssessment.createdAt,
                    TeacherAssessment.grade)
            .select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to false)
            .limit(1)
            .map { it[TeacherAssessment.grade] }
            .firstOrNull()

    if (teacherGrade != null)
        return teacherGrade

    val autoGrade = AutomaticAssessment
            .slice(AutomaticAssessment.submission,
                    AutomaticAssessment.createdAt,
                    AutomaticAssessment.grade)
            .select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to false)
            .limit(1)
            .map { it[AutomaticAssessment.grade] }
            .firstOrNull()

    return autoGrade
}
