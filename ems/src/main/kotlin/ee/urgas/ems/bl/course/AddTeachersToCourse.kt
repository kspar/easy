package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import ee.urgas.ems.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddTeachersToCourse {

    data class Req(@JsonProperty("teacher_id") val teacherId: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/teachers")
    fun addTeachersToCourse(@PathVariable("courseId") courseIdStr: String,
                            @RequestBody teachers: List<Req>,
                            caller: EasyUser) {

        log.debug { "Adding access to course $courseIdStr to teachers $teachers" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        insertTeacherCourseAccesses(courseId, teachers)
    }
}


private fun insertTeacherCourseAccesses(courseId: Long, teachers: List<AddTeachersToCourse.Req>) {

    transaction {
        teachers.forEach { teacher ->
            val teacherExists =
                    Teacher.select { Teacher.id eq teacher.teacherId }
                            .count() == 1
            if (!teacherExists) {
                throw InvalidRequestException("Teacher not found: $teacher")
            }
        }

        val teachersWithoutAccess = teachers.filter {
            TeacherCourseAccess.select {
                TeacherCourseAccess.teacher eq it.teacherId and (TeacherCourseAccess.course eq courseId)
            }.count() == 0
        }

        log.debug { "Granting access to teacher (the rest already have access): $teachersWithoutAccess" }

        TeacherCourseAccess.batchInsert(teachersWithoutAccess) { teacher ->
            this[TeacherCourseAccess.teacher] = EntityID(teacher.teacherId, Teacher)
            this[TeacherCourseAccess.course] = EntityID(courseId, Course)
        }
    }
}
