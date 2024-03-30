package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseGroup
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadCourseGroupsController {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("groups") val groups: List<GroupResp>)

    data class GroupResp(
        @JsonProperty("id") val id: String, @JsonProperty("name") val name: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/groups")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.info { "Getting all groups on course $courseIdStr for ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }

        return Resp(selectGroups(courseId))
    }

    private fun selectGroups(courseId: Long): List<GroupResp> = transaction {
        CourseGroup.selectAll().where { CourseGroup.course eq courseId }.map {
            GroupResp(it[CourseGroup.id].value.toString(), it[CourseGroup.name])
        }
    }
}

