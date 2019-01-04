package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.StudentCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class StudentReadCoursesController {

    data class StudentCoursesResponse(@JsonProperty("id") val id: String,
                                      @JsonProperty("title") val title: String)

    @GetMapping("/student/courses")
    fun readStudentCourses(): List<StudentCoursesResponse> {
        // TODO: get from auth
        val callerEmail = "ford"
        log.debug { "Getting courses for student $callerEmail" }
        val courses = selectCoursesForStudent(callerEmail)
        log.debug { "Found courses $courses" }
        return mapToStudentCoursesResponse(courses)
    }

    private fun mapToStudentCoursesResponse(courses: List<StudentCourse>) =
            courses.map { StudentCoursesResponse(it.id.toString(), it.title) }

}

data class StudentCourse(val id: Long, val title: String)

private fun selectCoursesForStudent(email: String): List<StudentCourse> {
    return transaction {
        (Student innerJoin StudentCourseAccess innerJoin Course)
                .slice(Course.id, Course.title)
                .select {
                    Student.id eq email
                }
                .withDistinct()
                .map {
                    StudentCourse(it[Course.id].value, it[Course.title])
                }
    }
}
