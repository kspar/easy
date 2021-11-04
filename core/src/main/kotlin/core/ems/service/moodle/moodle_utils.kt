package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Course
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


data class MoodleSyncedOperationResponse(
    @JsonProperty("status") val status: MoodleSyncStatus,
)

enum class MoodleSyncStatus { FINISHED, IN_PROGRESS }


fun assertCourseIsMoodleLinked(courseId: Long) {
    if (!isCourseMoodleLinked(courseId)) {
        throw InvalidRequestException("Course $courseId is not linked with Moodle")
    }
}

fun isCourseMoodleLinked(courseId: Long): Boolean {
    return selectCourseShortName(courseId) != null
}

fun selectCourseShortName(courseId: Long): String? {
    return transaction {
        Course.slice(Course.moodleShortName)
            .select {
                Course.id eq courseId
            }.map { it[Course.moodleShortName] }
            .singleOrNull()
    }
}
