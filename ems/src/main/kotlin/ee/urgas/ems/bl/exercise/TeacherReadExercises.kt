package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.db.AutomaticAssessment
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.db.GraderType
import ee.urgas.ems.db.StudentCourseAccess
import ee.urgas.ems.db.Submission
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherAssessment
import ee.urgas.ems.db.TeacherCourseAccess
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v1")
class TeacherReadCourseExercisesController {

    data class TeacherCourseExResp(@JsonProperty("id") val id: String,
                                   @JsonProperty("title") val title: String,
                                   @JsonSerialize(using = DateTimeSerializer::class)
                                   @JsonProperty("soft_deadline") val softDeadline: DateTime?,
                                   @JsonProperty("grader_type") val graderType: GraderType,
                                   @JsonProperty("unstarted_count") val unstartedCount: Int,
                                   @JsonProperty("graded_count") val gradedCount: Int?,
                                   @JsonProperty("ungraded_count") val ungradedCount: Int?,
                                   @JsonProperty("started_count") val startedCount: Int?,
                                   @JsonProperty("completed_count") val completedCount: Int?)

    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun readTeacherCourseExercises(@PathVariable("courseId") courseIdString: String): List<TeacherCourseExResp> {
        // TODO: get from auth
        val callerEmail = "ford"
        val courseId = courseIdString.toLong()

        if (!canTeacherAccessCourse(callerEmail, courseId)) {
            throw ForbiddenException("Teacher $callerEmail does not have access to course $courseIdString")
        }

        return mapToTeacherCourseExResp(selectTeacherExercisesOnCourse(courseId))
    }

    private fun mapToTeacherCourseExResp(exercises: List<TeacherCourseEx>): List<TeacherCourseExResp> =
            exercises.map {
                TeacherCourseExResp(
                        it.id.toString(), it.title, it.softDeadline, it.graderType, it.unstartedCount, it.gradedCount,
                        it.ungradedCount, it.startedCount, it.completedCount
                )
            }
}

// TODO: subclasses for different types?
data class TeacherCourseEx(val id: Long, val title: String, val softDeadline: DateTime?, val graderType: GraderType,
                           val gradedCount: Int?, val ungradedCount: Int?, val unstartedCount: Int,
                           val startedCount: Int?, val completedCount: Int?)


private fun canTeacherAccessCourse(email: String, courseId: Long): Boolean {
    return transaction {
        (Teacher innerJoin TeacherCourseAccess)
                .select { Teacher.id eq email and (TeacherCourseAccess.course eq courseId) }
                .count() > 0
    }
}

private fun selectTeacherExercisesOnCourse(courseId: Long): List<TeacherCourseEx> {
    return transaction {
        // Student count
        val studentCount = StudentCourseAccess
                .slice(StudentCourseAccess.student, StudentCourseAccess.course)
                .select { StudentCourseAccess.course eq courseId }
                .withDistinct()
                .count()

        data class SubmissionPartial(val id: Long, val email: String, val createdAt: DateTime)

        (Course innerJoin CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.id, CourseExercise.gradeThreshold, CourseExercise.softDeadline, Exercise.id,
                        Course.id, ExerciseVer.graderType, ExerciseVer.title, ExerciseVer.validTo)
                .select { Course.id eq courseId and ExerciseVer.validTo.isNull() }
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
                                val lastSub = lastSubmissions.get(it.email)
                                if (lastSub == null || lastSub.createdAt.isBefore(it.createdAt)) {
                                    lastSubmissions[it.email] = it
                                }
                            }

                    val lastSubmissionIds = lastSubmissions.values.map { it.id }
                    val submissionCount = lastSubmissionIds.size
                    val unstartedCount = studentCount - submissionCount

                    val graderType = ex[ExerciseVer.graderType]

                    when (graderType) {
                        GraderType.AUTO -> {
                            val completedCount = selectAutoExCompletedCount(
                                    lastSubmissionIds, ex[CourseExercise.gradeThreshold])
                            val startedCount = submissionCount - completedCount

                            TeacherCourseEx(
                                    ex[Exercise.id].value,
                                    ex[ExerciseVer.title],
                                    ex[CourseExercise.softDeadline],
                                    ex[ExerciseVer.graderType],
                                    null, null,
                                    unstartedCount,
                                    startedCount,
                                    completedCount
                            )
                        }
                        GraderType.TEACHER -> {
                            val gradedCount = selectTeacherExGradedCount(lastSubmissionIds)
                            val ungradedCount = submissionCount - gradedCount

                            TeacherCourseEx(
                                    ex[Exercise.id].value,
                                    ex[ExerciseVer.title],
                                    ex[CourseExercise.softDeadline],
                                    ex[ExerciseVer.graderType],
                                    gradedCount,
                                    ungradedCount,
                                    unstartedCount,
                                    null, null
                            )
                        }
                    }
                }
    }
}

private fun selectAutoExCompletedCount(lastSubmissionIds: List<Long>, threshold: Int): Int {
    return lastSubmissionIds.map {
        // TODO: should consider TeacherAssessment first if it exists
        AutomaticAssessment
                .slice(AutomaticAssessment.submission, AutomaticAssessment.createdAt, AutomaticAssessment.grade)
                .select { AutomaticAssessment.submission eq it }
                .orderBy(AutomaticAssessment.createdAt to false)
                .limit(1)
                .map { it[AutomaticAssessment.grade] }
                .firstOrNull()
    }.filter { it != null && it >= threshold }.count()
}

private fun selectTeacherExGradedCount(submissionIds: List<Long>): Int {
    return submissionIds.filter {
        TeacherAssessment
                .select { TeacherAssessment.submission eq it }
                .count() > 0
    }.count()
}

