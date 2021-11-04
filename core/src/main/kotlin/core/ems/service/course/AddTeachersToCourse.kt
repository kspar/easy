package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
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

    data class Req(
        @JsonProperty("teachers") @field:Valid val teachers: List<TeacherReq>
    )

    data class TeacherReq(
        @JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String,
        @JsonProperty("groups") @field:Valid val groups: List<GroupReq> = emptyList()
    )

    data class GroupReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100) val groupId: String
    )

    data class Resp(
        @JsonProperty("accesses_added") val accessesAdded: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/teachers")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        log.debug { "Adding access to course $courseIdStr to teachers $body by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)

        val accesses = body.teachers.distinctBy { it.email }.map {
            val id = getUsernameByEmail(it.email)
                ?: throw InvalidRequestException(
                    "Account with email ${it.email} not found",
                    ReqError.ACCOUNT_EMAIL_NOT_FOUND, "email" to it.email
                )
            val groupIds = it.groups.map { it.groupId.idToLongOrInvalidReq() }.toSet()
            TeacherNewAccess(id, it.email, groupIds)
        }

        accesses.flatMap { it.groups }.toSet().forEach {
            assertGroupExistsOnCourse(it, courseId)
        }

        return insertTeacherCourseAccesses(courseId, accesses)
    }
}

private data class TeacherNewAccess(val id: String, val email: String, val groups: Set<Long>)

private fun insertTeacherCourseAccesses(courseId: Long, newTeachers: List<TeacherNewAccess>): AddTeachersToCourse.Resp {
    val time = DateTime.now()

    val accessesAdded = transaction {

        newTeachers.forEach {
            if (!teacherExists(it.id)) {
                log.debug { "No teacher entity found for account ${it.id} (email: ${it.email}), creating it" }
                insertTeacher(it.id)
            }
        }

        val teachersWithoutAccess = newTeachers.filter {
            !canTeacherAccessCourse(it.id, courseId)
        }

        log.debug { "Granting access to teachers (the rest already have access): $teachersWithoutAccess" }

        TeacherCourseAccess.batchInsert(teachersWithoutAccess) {
            this[TeacherCourseAccess.teacher] = EntityID(it.id, Teacher)
            this[TeacherCourseAccess.course] = EntityID(courseId, Course)
            this[TeacherCourseAccess.createdAt] = time
        }

        teachersWithoutAccess.forEach { teacher ->
            TeacherCourseGroup.batchInsert(teacher.groups) { groupId ->
                this[TeacherCourseGroup.teacher] = EntityID(teacher.id, Teacher)
                this[TeacherCourseGroup.course] = EntityID(courseId, Course)
                this[TeacherCourseGroup.courseGroup] = EntityID(groupId, CourseGroup)
            }
        }

        teachersWithoutAccess.size
    }
    return AddTeachersToCourse.Resp(accessesAdded)
}

private fun insertTeacher(teacherId: String) {
    Teacher.insert {
        it[id] = EntityID(teacherId, Teacher)
        it[createdAt] = DateTime.now()
    }
}
