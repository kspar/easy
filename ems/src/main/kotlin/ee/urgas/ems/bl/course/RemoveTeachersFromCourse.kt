package ee.urgas.ems.bl.course

import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import ee.urgas.ems.exception.InvalidRequestException
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

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/teachers")
    fun removeTeachers(@PathVariable("courseId") courseIdStr: String,
                       @RequestBody teacherIds: List<String>,
                       caller: EasyUser) {

        log.debug { "Removing teachers $teacherIds from course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        deleteTeachersFromCourse(teacherIds, courseId)
    }
}


private fun deleteTeachersFromCourse(teacherIds: List<String>, courseId: Long) {
    transaction {
        teacherIds.forEach { teacherId ->
            val teacherExists =
                    Teacher.select { Teacher.id eq teacherId }
                            .count() == 1
            if (!teacherExists) {
                throw InvalidRequestException("Teacher not found: $teacherId")
            }
        }

        val teachersWithAccess = teacherIds.filter {
            TeacherCourseAccess.select {
                TeacherCourseAccess.teacher eq it and (TeacherCourseAccess.course eq courseId)
            }.count() > 0
        }


        teachersWithAccess.forEach { teacherId ->
            TeacherCourseAccess.deleteWhere {
                TeacherCourseAccess.teacher eq teacherId and (TeacherCourseAccess.course eq courseId)
            }
        }

        log.debug { "Removing access from teachers (the rest have already no access): $teachersWithAccess" }

    }
}
