package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.access.assertTeacherOrAdminHasAccessToCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.StudentCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadStudentsOnCourseController {

    data class StudentsOnCourseResponse(@JsonProperty("id") val id: String,
                                        @JsonProperty("given_name") val givenName: String,
                                        @JsonProperty("family_name") val familyName: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/students")
    fun readStudentsOnCourse(@PathVariable("courseId") courseIdStr: String, caller: EasyUser):
            List<StudentsOnCourseResponse> {

        log.debug { "Getting students on course for ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val students = selectStudentsOnCourse(courseId)
        log.debug { "Students on course $courseId: $students" }

        return mapToStudentsOnCourseResponse(students)
    }

    private fun mapToStudentsOnCourseResponse(students: List<StudentOnCourse>) =
            students.map { StudentsOnCourseResponse(it.id, it.givenName, it.familyName) }
}


data class StudentOnCourse(val id: String, val givenName: String, val familyName: String)


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


