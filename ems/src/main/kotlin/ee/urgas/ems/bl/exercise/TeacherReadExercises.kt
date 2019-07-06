package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import ee.urgas.ems.bl.access.canTeacherAccessCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.*
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
@RequestMapping("/v2")
class TeacherReadCourseExercisesController {

    data class TeacherCourseExResp(@JsonProperty("id") val id: String,
                                   @JsonProperty("title") val title: String,
                                   @JsonSerialize(using = DateTimeSerializer::class)
                                   @JsonProperty("soft_deadline") val softDeadline: DateTime?,
                                   @JsonProperty("grader_type") val graderType: GraderType,
                                   @JsonProperty("ordering_idx") val orderingIndex: Int,
                                   @JsonProperty("unstarted_count") val unstartedCount: Int,
                                   @JsonProperty("graded_count") val gradedCount: Int?,
                                   @JsonProperty("ungraded_count") val ungradedCount: Int?,
                                   @JsonProperty("started_count") val startedCount: Int?,
                                   @JsonProperty("completed_count") val completedCount: Int?)

    @Secured("ROLE_TEACHER")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun readTeacherCourseExercises(@PathVariable("courseId") courseIdString: String, caller: EasyUser):
            List<TeacherCourseExResp> {

        val callerId = caller.id
        val courseId = courseIdString.idToLongOrInvalidReq()

        if (!canTeacherAccessCourse(callerId, courseId)) {
            throw ForbiddenException("Teacher $callerId does not have access to course $courseIdString")
        }

        return mapToTeacherCourseExResp(selectTeacherExercisesOnCourse(courseId))
    }

    private fun mapToTeacherCourseExResp(exercises: List<TeacherCourseEx>): List<TeacherCourseExResp> =
            exercises.map {
                TeacherCourseExResp(
                        it.id.toString(), it.title, it.softDeadline, it.graderType, it.orderingIndex,
                        it.unstartedCount, it.gradedCount, it.ungradedCount, it.startedCount, it.completedCount
                )
            }
}

// TODO: subclasses for different types?
data class TeacherCourseEx(val id: Long, val title: String, val softDeadline: DateTime?, val graderType: GraderType,
                           val orderingIndex: Int, val gradedCount: Int?, val ungradedCount: Int?,
                           val unstartedCount: Int, val startedCount: Int?, val completedCount: Int?)


private fun selectTeacherExercisesOnCourse(courseId: Long): List<TeacherCourseEx> {
    return transaction {
        // Student count
        val studentCount = StudentCourseAccess
                .slice(StudentCourseAccess.student, StudentCourseAccess.course)
                .select { StudentCourseAccess.course eq courseId }
                .withDistinct()
                .count()

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
                    val submissionCount = lastSubmissionIds.size
                    val unstartedCount = studentCount - submissionCount

                    val graderType = ex[ExerciseVer.graderType]

                    when (graderType) {
                        GraderType.AUTO -> {
                            val completedCount = selectAutoExCompletedCount(
                                    lastSubmissionIds, ex[CourseExercise.gradeThreshold])
                            val startedCount = submissionCount - completedCount

                            TeacherCourseEx(
                                    ex[CourseExercise.id].value,
                                    ex[ExerciseVer.title],
                                    ex[CourseExercise.softDeadline],
                                    ex[ExerciseVer.graderType],
                                    ex[CourseExercise.orderIdx],
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
                                    ex[CourseExercise.id].value,
                                    ex[ExerciseVer.title],
                                    ex[CourseExercise.softDeadline],
                                    ex[ExerciseVer.graderType],
                                    ex[CourseExercise.orderIdx],
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

