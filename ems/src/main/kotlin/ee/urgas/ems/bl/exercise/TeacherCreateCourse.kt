package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Teacher
import ee.urgas.ems.db.TeacherCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherCreateCourseController {

    data class NewCourseBody(@JsonProperty("title", required = true) val title: String)

    @Secured("ROLE_TEACHER")
    @PostMapping("/teacher/courses")
    fun createExercise(@RequestBody dto: NewCourseBody, caller: EasyUser) {
        log.debug { "Create course '${dto.title}' by ${caller.id}" }

        val callerId = caller.id
        insertCourse(callerId, dto)
    }
}


private fun insertCourse(ownerId: String, body: TeacherCreateCourseController.NewCourseBody) {
    val teacherId = EntityID(ownerId, Teacher)

    transaction {
        val courseId =
                Course.insertAndGetId {
                    it[createdAt] = DateTime.now()
                    it[title] = body.title
                }

        TeacherCourseAccess.insert {
            it[teacher] = teacherId
            it[course] = courseId
        }
    }
}
