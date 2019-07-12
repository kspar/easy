package core.ems.service

import core.db.Submission
import org.jetbrains.exposed.sql.select
import org.joda.time.DateTime

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
