package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Course
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service


data class MoodleSyncedOperationResponse(@get:JsonProperty("status") val status: MoodleSyncStatus)

enum class MoodleSyncStatus { FINISHED, IN_PROGRESS }


/**
 * Which Moodle courses this environment may talk to at all, by shortname.
 *
 * **Empty means unrestricted**, which is what production wants: it syncs whatever courses teachers
 * have linked, and an allowlist there would be a list nobody remembers to maintain. The setting
 * exists for environments that must reach a real Moodle without being trusted with all of it — a
 * staging host pointed at one throwaway course, say.
 *
 * Why it is enforced at the last moment before the HTTP call rather than at the endpoints: grades do
 * not reach Moodle through an endpoint at all. `syncSingleGradeToMoodle` is called from ordinary
 * grading — `submissions.kt`, `TeacherPostGrade`, `TeacherRetryAutoassess` — so anyone submitting or
 * grading anything pushes to the gradebook. A check on the two sync endpoints would have looked
 * complete and covered none of that. Gating where the request is built covers every caller that
 * exists and every caller anyone adds later.
 *
 * A comma-separated string rather than a YAML list because `@Value` cannot bind a list; SecurityConf
 * has the same note about CORS origins.
 */
@Service
class MoodleCourseAllowlist {
    private val log = KotlinLogging.logger {}

    @Value($$"${easy.core.moodle-sync.course-allowlist:}")
    private lateinit var raw: String

    private val allowed: Set<String>
        get() = raw.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    fun isAllowed(shortname: String): Boolean = allowed.isEmpty() || shortname in allowed

    /**
     * The guarantee. Throws rather than returning a boolean at the call sites that actually build a
     * request, so that "we never contacted Moodle about that course" is a property of the code and
     * not of every caller having remembered to ask.
     */
    fun assertAllowed(shortname: String) {
        if (!isAllowed(shortname)) {
            log.warn { "Refusing to contact Moodle about course '$shortname': not in easy.core.moodle-sync.course-allowlist" }
            throw InvalidRequestException(
                "Moodle course '$shortname' is not allowed on this environment.",
                ReqError.MOODLE_LINKING_ERROR, notify = false
            )
        }
    }
}


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
