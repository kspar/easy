package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherCourseAccess
import core.ems.service.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.canTeacherAccessCourse
import core.ems.service.access_control.teacherOnCourse
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class AddTeachersToCourse {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("teachers") @field:Valid val teachers: List<TeacherReq>
    )

    data class TeacherReq(
        @param:JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String,
        @param:JsonProperty("groups") @field:Valid val groups: List<GroupReq> = emptyList()
    )

    data class GroupReq(
        @param:JsonProperty("id") @field:NotBlank @field:Size(max = 100) val groupId: String
    )

    data class Resp(
        @get:JsonProperty("accesses_added") val accessesAdded: Int
    )

    private data class TeacherNewAccess(val id: String, val email: String, val groups: Set<Long>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/teachers")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        log.info { "Adding access to course $courseIdStr to teachers $body by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        val requested = body.teachers.distinctBy { it.email }
        val resolved = requested.map { it to getUsernameByEmail(it.email) }

        // Every address is looked up before any is reported. This is a bulk paste — a teacher adds
        // a course's staff in one go — and throwing on the first unknown address named one line of
        // the list and said nothing about the other twenty-nine, so the only way to find out which
        // ones were wrong was to submit them one at a time (EZ-1830).
        val missing = resolved.filter { it.second == null }.map { it.first.email }
        if (missing.isNotEmpty()) {
            val attrs = buildList {
                add("emails" to missing.joinToString(", "))
                // Kept for the single-address case so a client that only knows the old attribute
                // still renders the address rather than a generic failure.
                if (missing.size == 1) add("email" to missing.single())
            }
            throw InvalidRequestException(
                "No account with email(s) ${missing.joinToString(", ")}",
                ReqError.ACCOUNT_EMAIL_NOT_FOUND, *attrs.toTypedArray(), notify = false
            )
        }

        val accesses = resolved.map { (teacher, id) ->
            val groupIds = teacher.groups.map { it.groupId.idToLongOrInvalidReq() }.toSet()
            TeacherNewAccess(id!!, teacher.email, groupIds)
        }

        accesses.flatMap { it.groups }.toSet().forEach {
            assertGroupExistsOnCourse(it, courseId)
        }

        return insertTeacherCourseAccesses(courseId, accesses)
    }


    private fun insertTeacherCourseAccesses(courseId: Long, newTeachers: List<TeacherNewAccess>) = transaction {

        newTeachers.forEach {
            if (!teacherExists(it.id)) {
                log.debug { "No teacher entity found for account ${it.id} (email: ${it.email}), creating it" }
                insertTeacher(it.id)
            }
        }

        val teachersWithoutAccess = newTeachers.filter { !canTeacherAccessCourse(it.id, courseId) }

        log.debug { "Granting access to teachers (the rest already have access): $teachersWithoutAccess" }

        val time = DateTime.now()
        TeacherCourseAccess.batchInsert(teachersWithoutAccess) {
            this[TeacherCourseAccess.teacher] = it.id
            this[TeacherCourseAccess.course] = courseId
            this[TeacherCourseAccess.createdAt] = time
        }
        Resp(teachersWithoutAccess.size)
    }
}


