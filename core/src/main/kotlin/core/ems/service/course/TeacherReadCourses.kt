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

    // TODO: refactor using teacher query

    data class CourseResp(@JsonProperty("id") val id: String,
                          @JsonProperty("title") val title: String,
                          @JsonProperty("student_count") val studentCount: Int)

    data class Resp(@JsonProperty("courses") val courses: List<CourseResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses")
    fun controller(caller: EasyUser): Resp {
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
        return courses
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

private fun selectCoursesForTeacher(teacherId: String): TeacherReadCoursesController.Resp {
    return transaction {
        TeacherReadCoursesController.Resp(
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

                            TeacherReadCoursesController.CourseResp(course.first.value.toString(), course.second, studentCount)
                        })
    }
}
