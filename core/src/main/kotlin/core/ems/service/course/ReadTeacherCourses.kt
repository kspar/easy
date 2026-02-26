package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
import core.db.TeacherCourseAccess
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.annotation.JsonSerialize


@RestController
@RequestMapping("/v2")
class ReadTeacherCourses {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("courses") val courses: List<CourseResp>)

    data class CourseResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("alias") val alias: String?,
        @get:JsonProperty("course_code") val courseCode: String?,
        @get:JsonProperty("archived") val archived: Boolean,
        @get:JsonProperty("student_count") val studentCount: Long,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_accessed") val lastAccessed: DateTime,
        @get:JsonProperty("moodle_short_name") val moodleShortName: String?,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_submission_at") val lastSubmissionAt: DateTime?,
        @get:JsonProperty("color") val color: String,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses")
    fun controller(caller: EasyUser): Resp {
        val callerId = caller.id

        val courses = if (caller.isAdmin()) {
            log.info { "Getting courses for admin $callerId" }
            selectCoursesForAdmin()
        } else {
            log.info { "Getting courses for teacher $callerId" }
            selectCoursesForTeacher(callerId)
        }

        return Resp(courses)
    }

    private fun selectCoursesForAdmin(): List<CourseResp> = transaction {
        val currentTime = DateTime.now()

        val studentCount = StudentCourseAccess.student.count().alias("student_count")
        (Course leftJoin StudentCourseAccess)
            .select(
                Course.id,
                Course.title,
                Course.alias,
                Course.courseCode,
                Course.archived,
                Course.moodleShortName,
                Course.lastSubmissionAt,
                Course.color,
                studentCount
            )
            .groupBy(
                Course.id,
                Course.title,
                Course.alias,
                Course.courseCode,
                Course.archived,
                Course.moodleShortName,
                Course.lastSubmissionAt,
                Course.color
            )
            .map {
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    it[Course.courseCode],
                    it[Course.archived],
                    it[studentCount],
                    currentTime,
                    it[Course.moodleShortName],
                    it[Course.lastSubmissionAt],
                    it[Course.color],
                )
            }
    }

    private fun selectCoursesForTeacher(teacherId: String): List<CourseResp> = transaction {
        // get teacher course accesses with groups
        (Course innerJoin TeacherCourseAccess)
            .select(
                Course.id,
                Course.title,
                Course.alias,
                Course.courseCode,
                Course.archived,
                Course.moodleShortName,
                Course.lastSubmissionAt,
                Course.color,
                TeacherCourseAccess.lastAccessed
            )
            .where { TeacherCourseAccess.teacher eq teacherId }
            .map {
                // Get student count for each course
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    it[Course.courseCode],
                    it[Course.archived],
                    selectStudentCountForCourse(it[Course.id].value),
                    it[TeacherCourseAccess.lastAccessed],
                    it[Course.moodleShortName],
                    it[Course.lastSubmissionAt],
                    it[Course.color],
                )
            }
    }

    private fun selectStudentCountForCourse(courseId: Long): Long =
        // Select distinct students, ignoring their groups
        (StudentCourseAccess leftJoin StudentCourseGroup)
            .select(StudentCourseAccess.student)
            .where { StudentCourseAccess.course eq courseId }
            .withDistinct()
            .count()
}