package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.StudentCourseAccess
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class TeacherReadCoursesController {

    data class TeacherCoursesResponse(@JsonProperty("id") val id: String,
                                      @JsonProperty("title") val title: String,
                                      @JsonProperty("student_count") val studentCount: Int)

    @GetMapping("/teacher/courses")
    fun readTeacherCourses(): List<TeacherCoursesResponse> {
        // TODO: get from auth
        val callerEmail = "ford"
        log.debug { "Getting courses for teacher $callerEmail" }
        val courses = selectCoursesForTeacher(callerEmail)
        log.debug { "Found courses $courses" }
        return mapToTeacherCoursesResponse(courses)
    }

    private fun mapToTeacherCoursesResponse(courses: List<TeacherCourse>) =
            courses.map { TeacherCoursesResponse(it.id.toString(), it.title, it.studentCount) }

}


data class TeacherCourse(val id: Long, val title: String, val studentCount: Int)


private fun selectCoursesForTeacher(email: String): List<TeacherCourse> {
    return transaction {
        (Teacher innerJoin TeacherCourseAccess innerJoin Course)
                .slice(Course.id, Course.title)
                .select {
                    Teacher.id eq email
                }
                .withDistinct()
                .map {
                    Pair(it[Course.id], it[Course.title])
                }
                .map { course ->
                    val studentCount =
                            (Student innerJoin StudentCourseAccess innerJoin Course)
                                    .slice(Course.id, Student.id)  // exclude id from distinct
                                    .select { Course.id eq course.first }
                                    .withDistinct()
                                    .count()

                    TeacherCourse(course.first.value, course.second, studentCount)
                }
    }
}
