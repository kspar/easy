package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.StudentCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class ReadStudentsOnCourseController {

    data class StudentsOnCourseResponse(@JsonProperty("email") val email: String,
                                        @JsonProperty("given_name") val givenName: String,
                                        @JsonProperty("family_name") val familyName: String)

    @GetMapping("/teacher/courses/{courseId}/students")
    fun readStudentsOnCourse(@PathVariable("courseId") courseId: String): List<StudentsOnCourseResponse> {
        val students = selectStudentsOnCourse(courseId.toLong())
        log.debug { "Students on course $courseId: $students" }
        return mapToStudentsOnCourseResponse(students)
    }

    private fun mapToStudentsOnCourseResponse(students: List<StudentOnCourse>) =
            students.map { StudentsOnCourseResponse(it.email, it.givenName, it.familyName) }
}


data class StudentOnCourse(val email: String, val givenName: String, val familyName: String)


private fun selectStudentsOnCourse(courseId: Long): List<StudentOnCourse> {
    return transaction {
        (Student innerJoin StudentCourseAccess innerJoin Course)
                .slice(Student.id, Student.givenName, Student.familyName)
                .select { Course.id eq courseId }
                .withDistinct()
                .map {
                    StudentOnCourse(
                            it[Student.id].value,
                            it[Student.givenName],
                            it[Student.familyName]
                    )
                }
    }
}


