package ee.urgas.ems.bl.course

import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
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

    @Secured("ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/teachers")
    fun addTeachersToCourse(@PathVariable("courseId") courseIdStr: String,
                            @RequestBody teacherIds: List<String>,
                            caller: EasyUser) {

        log.debug { "Adding access to course $courseIdStr to teachers $teacherIds" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        insertTeacherCourseAccesses(courseId, teacherIds)
    }
}

class TeacherNotFoundException(override val message: String) : RuntimeException(message)

private fun insertTeacherCourseAccesses(courseId: Long, teacherIds: List<String>) {

    transaction {
        teacherIds.forEach { teacherId ->
            val teacherExists =
                    Teacher.select { Teacher.id eq teacherId }
                            .count() == 1
            if (!teacherExists) {
                throw TeacherNotFoundException(teacherId)
            }
        }

        val teachersWithoutAccess = teacherIds.filter {
            TeacherCourseAccess.select {
                TeacherCourseAccess.teacher eq it and (TeacherCourseAccess.course eq courseId)
            }.count() == 0
        }

        log.debug { "Granting access to teacher (the rest already have access): $teachersWithoutAccess" }

        TeacherCourseAccess.batchInsert(teachersWithoutAccess) { id ->
            this[TeacherCourseAccess.teacher] = EntityID(id, Teacher)
            this[TeacherCourseAccess.course] = EntityID(courseId, Course)
        }
    }
}
