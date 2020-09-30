package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentCourseAccess
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

    data class CourseResp(@JsonProperty("id") val id: String,
                          @JsonProperty("title") val title: String,
                          @JsonProperty("student_count") val studentCount: Long)

    data class Resp(@JsonProperty("courses") val courses: List<CourseResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses")
    fun controller(caller: EasyUser): Resp {
        val callerId = caller.id

        return if (caller.isAdmin()) {
            log.debug { "Getting courses for admin $callerId" }
            selectCoursesForAdmin()
        } else {
            log.debug { "Getting courses for teacher $callerId" }
            selectCoursesForTeacher(callerId)
        }
    }
}

private fun selectCoursesForAdmin(): TeacherReadCoursesController.Resp = transaction {
    TeacherReadCoursesController.Resp(
            Course.slice(Course.id, Course.title)
                    .selectAll()
                    .map {
                        val studentCount = StudentCourseAccess
                                .select { StudentCourseAccess.course eq it[Course.id] }
                                .count()
                        TeacherReadCoursesController.CourseResp(it[Course.id].value.toString(), it[Course.title], studentCount)
                    })
}

private fun selectCoursesForTeacher(teacherId: String): TeacherReadCoursesController.Resp = transaction {
    TeacherReadCoursesController.Resp(
            (Course innerJoin TeacherCourseAccess)
                    .slice(Course.id, Course.title)
                    .select {
                        TeacherCourseAccess.teacher eq teacherId
                    }
                    .map {
                        val studentCount = StudentCourseAccess
                                .select { StudentCourseAccess.course eq it[Course.id] }
                                .count()
                        TeacherReadCoursesController.CourseResp(it[Course.id].value.toString(), it[Course.title], studentCount)
                    })
}
