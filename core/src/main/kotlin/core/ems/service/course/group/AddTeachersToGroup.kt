package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.Group
import core.db.Teacher
import core.db.TeacherGroupAccess
import core.ems.service.*
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddTeachersToGroupController {

    data class Req(
            @JsonProperty("teachers")
            @field:Valid
            val teachers: List<TeacherReq>
    )

    data class TeacherReq(
            @JsonProperty("username")
            @field:NotBlank @field:Size(max = 100)
            val username: String
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/groups/{groupId}/teachers")
    fun controller(
            @PathVariable("courseId") courseIdStr: String,
            @PathVariable("groupId") groupIdStr: String,
            @Valid @RequestBody dto: Req,
            caller: EasyUser
    ) {
        log.debug { "Add teachers $dto to group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)

        addTeachersToGroup(courseId, groupId, dto.teachers.map { it.username })
    }
}

private fun addTeachersToGroup(courseId: Long, groupId: Long, teacherUsernames: List<String>) {
    transaction {
        teacherUsernames.forEach { teacherUsername ->
            if (!canTeacherAccessCourse(teacherUsername, courseId)) {
                throw InvalidRequestException("Teacher $teacherUsername does not have access to course $courseId")
            }

            TeacherGroupAccess.insertIgnore {
                it[teacher] = EntityID(teacherUsername, Teacher)
                it[course] = EntityID(courseId, Course)
                it[group] = EntityID(groupId, Group)
            }
        }
    }
}
