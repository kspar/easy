package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.Teacher
import core.db.TeacherCourseAccess
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddTeachersToCourse {

    data class Req(@JsonProperty("teachers") @field:Valid val teachers: List<TeacherReq>)

    data class TeacherReq(@JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/teachers")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @Valid @RequestBody teachers: Req,
                   caller: EasyUser) {

        log.debug { "Adding access to course $courseIdStr to teachers $teachers" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        assertCourseExists(courseId)

        insertTeacherCourseAccesses(courseId, teachers)
    }
}


private fun insertTeacherCourseAccesses(courseId: Long, teachers: AddTeachersToCourse.Req) {
    val time = DateTime.now()
    transaction {
        val teachersWithoutAccess =
                teachers.teachers.map {
                    val username = getUsernameByEmail(it.email)
                            ?: throw InvalidRequestException("Account with email ${it.email} not found",
                                    ReqError.ACCOUNT_EMAIL_NOT_FOUND, "email" to it.email)

                    if (!teacherExists(username)) {
                        log.debug { "No teacher entity found for account $username (email: ${it.email}), creating it" }
                        insertTeacher(username)
                    }
                    username
                }.filter {
                    !canTeacherAccessCourse(it, courseId)
                }

        log.debug { "Granting access to teachers (the rest already have access): $teachersWithoutAccess" }

        TeacherCourseAccess.batchInsert(teachersWithoutAccess) {
            this[TeacherCourseAccess.teacher] = EntityID(it, Teacher)
            this[TeacherCourseAccess.course] = EntityID(courseId, Course)
            this[TeacherCourseAccess.createdAt] = time
        }
    }
}

private fun insertTeacher(teacherId: String) {
    Teacher.insert {
        it[id] = EntityID(teacherId, Teacher)
        it[createdAt] = DateTime.now()
    }
}
