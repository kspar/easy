package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherCourseGroup
import core.ems.service.*
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddTeachersToCourseGroupController {

    data class Req(
        @JsonProperty("teachers") @field:Valid val teachers: List<TeacherReq>
    )

    data class TeacherReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/groups/{groupId}/teachers")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("groupId") groupIdStr: String,
        @Valid @RequestBody dto: Req,
        caller: EasyUser
    ) {
        val teacherIds = dto.teachers.map { it.id }
        log.debug { "Add teachers $teacherIds to group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        if (teacherIds.contains(caller.id)) {
            throw InvalidRequestException("You cannot add yourself to a group")
        }
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)

        teacherIds.forEach {
            if (!canTeacherAccessCourse(it, courseId)) {
                throw InvalidRequestException("Teacher $it does not have access to course $courseId")
            }
        }

        addTeachersToGroup(courseId, groupId, teacherIds)
    }
}

private fun addTeachersToGroup(courseId: Long, groupId: Long, teacherIds: List<String>) {
    transaction {
        TeacherCourseGroup.batchInsert(teacherIds, ignore = true) {
            this[TeacherCourseGroup.teacher] = it
            this[TeacherCourseGroup.courseGroup] = groupId
            this[TeacherCourseGroup.course] = courseId
        }
    }
}
