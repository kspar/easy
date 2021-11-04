package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseGroup
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasNoRestrictedGroupsOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class CreateCourseGroupController {

    data class Req(
            @JsonProperty("name", required = true)
            @field:NotBlank @field:Size(max = 200)
            val name: String
    )

    data class Resp(
            @JsonProperty("id") val id: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/groups")
    fun controller(
            @PathVariable("courseId") courseIdStr: String,
            @Valid @RequestBody dto: Req,
            caller: EasyUser
    ): Resp {
        log.debug { "Create group '${dto.name}' on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)

        val groupId = createGroup(courseId, dto.name)
        return Resp(groupId.toString())
    }
}


private fun createGroup(courseId: Long, groupName: String): Long {
    return transaction {
        CourseGroup.insertAndGetId {
            it[name] = groupName
            it[course] = EntityID(courseId, Course)
        }
    }.value
}