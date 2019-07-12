package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentCourseAccess
import core.db.Teacher
import core.db.TeacherCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadCoursesController {

    // TODO: refactor using response object & teacher query

    data class TeacherCoursesResponse(@JsonProperty("id") val id: String,
                                      @JsonProperty("title") val title: String,
                                      @JsonProperty("student_count") val studentCount: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses")
    fun readTeacherCourses(caller: EasyUser): List<TeacherCoursesResponse> {
        val callerId = caller.id

        val courses =
                if (caller.isAdmin()) {
                    log.debug { "Getting courses for admin $callerId" }
                    selectCoursesForAdmin()
                } else {
                    log.debug { "Getting courses for teacher $callerId" }
                    selectCoursesForTeacher(callerId)
                }

        log.debug { "Found courses $courses" }
        return mapToTeacherCoursesResponse(courses)
    }

    private fun mapToTeacherCoursesResponse(courses: List<TeacherCourse>) =
            courses.map { TeacherCoursesResponse(it.id.toString(), it.title, it.studentCount) }

}


data class TeacherCourse(val id: Long, val title: String, val studentCount: Int)


private fun selectCoursesForAdmin(): List<TeacherCourse> = transaction {
    Course.slice(Course.id, Course.title)
            .selectAll()
            .map {
                val studentCount = StudentCourseAccess
                        .select { StudentCourseAccess.course eq it[Course.id] }
                        .count()
                TeacherCourse(it[Course.id].value, it[Course.title], studentCount)
            }
}

private fun selectCoursesForTeacher(teacherId: String): List<TeacherCourse> {
    return transaction {
        (Teacher innerJoin TeacherCourseAccess innerJoin Course)
                .slice(Course.id, Course.title)
                .select {
                    Teacher.id eq teacherId
                }
                .withDistinct()
                .map {
                    Pair(it[Course.id], it[Course.title])
                }
                .map { course ->
                    val studentCount =
                            StudentCourseAccess
                                    .slice(StudentCourseAccess.course, StudentCourseAccess.student)  // exclude id from distinct
                                    .select { StudentCourseAccess.course eq course.first }
                                    .withDistinct()
                                    .count()

                    TeacherCourse(course.first.value, course.second, studentCount)
                }
    }
}
