package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Teacher
import core.db.TeacherCourseAccess
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveTeachersFromCourseController {

    data class Req(@JsonProperty("teacher_id") val teacherId: String)


    @Secured("ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/teachers")
    fun removeTeachers(@PathVariable("courseId") courseIdStr: String,
                       @RequestBody teachers: List<Req>,
                       caller: EasyUser) {

        log.debug { "Removing teachers $teachers from course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        deleteTeachersFromCourse(teachers, courseId)
    }
}


private fun deleteTeachersFromCourse(teachers: List<RemoveTeachersFromCourseController.Req>, courseId: Long) {
    transaction {
        teachers.forEach { teacher ->
            val teacherExists =
                    Teacher.select { Teacher.id eq teacher.teacherId }
                            .count() == 1
            if (!teacherExists) {
                throw InvalidRequestException("Teacher not found: $teacher")
            }
        }

        val teachersWithAccess = teachers.filter {
            TeacherCourseAccess.select {
                TeacherCourseAccess.teacher eq it.teacherId and (TeacherCourseAccess.course eq courseId)
            }.count() > 0
        }


        teachersWithAccess.forEach { teacher ->
            TeacherCourseAccess.deleteWhere {
                TeacherCourseAccess.teacher eq teacher.teacherId and (TeacherCourseAccess.course eq courseId)
            }
        }

        log.debug { "Removing access from teachers (the rest have already no access): $teachersWithAccess" }

    }
}
