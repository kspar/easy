package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseGroup
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class CreateCourseGroupController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("name", required = true)
        @field:NotBlank @field:Size(max = 200)
        val name: String
    )

    data class Resp(
        @get:JsonProperty("id") val id: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/groups")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody dto: Req,
        caller: EasyUser
    ): Resp {
        log.info { "Create group '${dto.name}' on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        val groupId = createGroup(courseId, dto.name)
        return Resp(groupId.toString())
    }

    private fun createGroup(courseId: Long, groupName: String): Long = transaction {
        CourseGroup.insertAndGetId {
            it[name] = groupName
            it[course] = EntityID(courseId, Course)
        }
    }.value
}


