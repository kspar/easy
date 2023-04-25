package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Course
import core.db.CourseExercise
import core.db.Submission
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

fun submissionExists(submissionId: Long, courseExId: Long, courseId: Long): Boolean = transaction {
    (Course innerJoin CourseExercise innerJoin Submission).select {
        Course.id eq courseId and (CourseExercise.id eq courseExId) and (Submission.id eq submissionId)
    }.count() == 1L
}

fun DateTime.hasSecondsPassed(seconds: Int) = this.plusSeconds(seconds).isAfterNow

fun assertSubmissionExists(submissionId: Long, courseExId: Long, courseId: Long) {
    if (!submissionExists(submissionId, courseExId, courseId)) {
        throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
    }
}

fun selectStudentBySubmissionId(submissionId: Long) =
    transaction { Submission.select { Submission.id eq submissionId }.map { it[Submission.student] }.single() }


data class GradeResp(@JsonProperty("grade") val grade: Int, @JsonProperty("is_autograde") val isAutograde: Boolean)


fun getSubmissionNumbers(studentId: String, courseExId: Long) = transaction {
    Submission.slice(Submission.id)
        .select { Submission.student eq studentId and (CourseExercise.id eq courseExId) }
        .orderBy(Submission.createdAt, SortOrder.ASC).map { it[Submission.id].value }
        .mapIndexed { index, id -> id to index + 1 }.toMap()
}


/**
 * Return latest submissions for this exercise.
 */
fun selectLatestSubmissionsForExercise(courseExerciseId: Long): List<Long> {
    data class SubmissionPartial(val id: Long, val studentId: String, val createdAt: DateTime)

    // student_id -> submission
    val latestSubmissions = HashMap<String, SubmissionPartial>()

    Submission
            .slice(Submission.id,
                    Submission.student,
                    Submission.createdAt,
                    Submission.courseExercise)
            .select { Submission.courseExercise eq courseExerciseId }
            .map {
                SubmissionPartial(
                        it[Submission.id].value,
                        it[Submission.student].value,
                        it[Submission.createdAt]
                )
            }
            .forEach {
                val lastSub = latestSubmissions[it.studentId]
                if (lastSub == null || lastSub.createdAt.isBefore(it.createdAt)) {
                    latestSubmissions[it.studentId] = it
                }
            }

    return latestSubmissions.values.map { it.id }
}
