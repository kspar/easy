package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Course
import core.exception.InvalidRequestException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


data class MoodleSyncedOperationResponse(@get:JsonProperty("status") val status: MoodleSyncStatus)

enum class MoodleSyncStatus { FINISHED, IN_PROGRESS }


fun assertCourseIsMoodleLinked(
    courseId: Long,
    requireStudentsSynced: Boolean = false,
    requireGradesSynced: Boolean = false
) {
    if (!isCourseMoodleLinked(courseId, requireStudentsSynced, requireGradesSynced)) {
        throw InvalidRequestException("Course $courseId is not linked with Moodle")
    }
}

fun isCourseMoodleLinked(courseId: Long, requireStudentsSynced: Boolean, requireGradesSynced: Boolean): Boolean {
    return selectCourseShortName(courseId, requireStudentsSynced, requireGradesSynced) != null
}

fun selectCourseShortName(
    courseId: Long,
    requireStudentsSynced: Boolean = false,
    requireGradesSynced: Boolean = false
): String? = transaction {
    Course.select(Course.moodleShortName, Course.moodleSyncStudents, Course.moodleSyncGrades)
        .where { Course.id eq courseId }.map {
            if (requireStudentsSynced && !it[Course.moodleSyncStudents]) {
                return@transaction null
            }
            if (requireGradesSynced && !it[Course.moodleSyncGrades]) {
                return@transaction null
            }

            it[Course.moodleShortName]
        }
        .singleOrNull()
}
