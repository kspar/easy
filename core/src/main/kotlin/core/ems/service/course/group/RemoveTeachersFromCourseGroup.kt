package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.TeacherCourseGroup
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasNoRestrictedGroupsOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveTeachersFromCourseGroupController {

    data class Req(
        @JsonProperty("teachers") @field:Valid val teachers: List<TeacherReq>
    )

    data class TeacherReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String
    )

    data class Resp(
        @JsonProperty("deleted_count") val deletedCount: Int
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/groups/{groupId}/teachers")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("groupId") groupIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {
        val teacherIds = body.teachers.map { it.id }
        log.debug { "Remove teachers $teacherIds from group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)

        val deletedCount = removeTeachersFromGroup(courseId, groupId, teacherIds)
        return Resp(deletedCount)
    }
}

private fun removeTeachersFromGroup(courseId: Long, groupId: Long, teachers: List<String>): Int {
    return transaction {
        TeacherCourseGroup.deleteWhere {
            TeacherCourseGroup.teacher.inList(teachers) and
                    TeacherCourseGroup.courseGroup.eq(groupId) and
                    TeacherCourseGroup.course.eq(courseId)
        }
    }
}
