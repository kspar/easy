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

    data class CourseTitleResp(@JsonProperty("id") val id: String, @JsonProperty("title") val title: String)

    data class Resp(@JsonProperty("courses") val courses: List<CourseTitleResp>)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses")
    fun controller(caller: EasyUser): Resp {
        val callerId = caller.id
        log.debug { "Getting courses for student $callerId" }
        return selectCoursesForStudent(callerId)
    }
}

private fun selectCoursesForStudent(studentId: String): StudentReadCoursesController.Resp {
    return transaction {
        StudentReadCoursesController.Resp(
                (Student innerJoin StudentCourseAccess innerJoin Course)
                        .slice(Course.id, Course.title)
                        .select {
                            Student.id eq studentId
                        }
                        .withDistinct()
                        .map {
                            StudentReadCoursesController.CourseTitleResp(it[Course.id].value.toString(), it[Course.title])
                        })
    }
}
