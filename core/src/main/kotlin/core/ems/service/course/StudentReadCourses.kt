package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.Student
import core.db.StudentCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentReadCoursesController {

    data class StudentCoursesResponse(@JsonProperty("id") val id: String,
                                      @JsonProperty("title") val title: String)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses")
    fun readStudentCourses(caller: EasyUser): List<StudentCoursesResponse> {
        val callerId = caller.id
        log.debug { "Getting courses for student $callerId" }
        val courses = selectCoursesForStudent(callerId)
        log.debug { "Found courses $courses" }
        return mapToStudentCoursesResponse(courses)
    }

    private fun mapToStudentCoursesResponse(courses: List<StudentCourse>) =
            courses.map { StudentCoursesResponse(it.id.toString(), it.title) }
}

data class StudentCourse(val id: Long, val title: String)

private fun selectCoursesForStudent(studentId: String): List<StudentCourse> {
    return transaction {
        (Student innerJoin StudentCourseAccess innerJoin Course)
                .slice(Course.id, Course.title)
                .select {
                    Student.id eq studentId
                }
                .withDistinct()
                .map {
                    StudentCourse(it[Course.id].value, it[Course.title])
                }
    }
}
