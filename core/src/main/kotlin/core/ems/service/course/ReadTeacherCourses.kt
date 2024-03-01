package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
import core.db.TeacherCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadTeacherCourses {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("courses") val courses: List<CourseResp>
    )

    data class CourseResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("alias") val alias: String?,
        @JsonProperty("student_count") val studentCount: Long
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
        val studentCount = StudentCourseAccess.student.count().alias("student_count")
        (Course leftJoin StudentCourseAccess)
            .slice(Course.id, Course.title, Course.alias, studentCount)
            .selectAll()
            .groupBy(Course.id, Course.title, Course.alias)
            .map {
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    it[studentCount],
                )
            }
    }

    private fun selectCoursesForTeacher(teacherId: String): List<CourseResp> = transaction {
        // get teacher course accesses with groups
        (Course innerJoin TeacherCourseAccess)
            .slice(Course.id, Course.title, Course.alias)
            .select {
                TeacherCourseAccess.teacher eq teacherId
            }
            .map {
                // Get student count for each course
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    selectStudentCountForCourse(it[Course.id].value)
                )
            }
    }

    private fun selectStudentCountForCourse(courseId: Long): Long =
        // Select distinct students, ignoring their groups
        (StudentCourseAccess leftJoin StudentCourseGroup)
            .slice(StudentCourseAccess.student)
            .select { StudentCourseAccess.course eq courseId }
            .withDistinct()
            .count()
}