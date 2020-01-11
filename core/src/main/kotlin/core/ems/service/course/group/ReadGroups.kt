package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Group
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.getTeacherRestrictedGroups
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadGroupsController {

    data class Resp(@JsonProperty("groups") val groups: List<GroupResp>)

    data class GroupResp(@JsonProperty("id") val id: String,
                         @JsonProperty("name") val name: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/groups")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   caller: EasyUser): Resp {

        log.debug { "Getting all groups on course $courseIdStr for ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return Resp(selectGroups(courseId, caller.id))
    }
}

private fun selectGroups(courseId: Long, callerId: String): List<ReadGroupsController.GroupResp> {
    return transaction {

        val restrictedGroups = getTeacherRestrictedGroups(courseId, callerId)

        val query = Group
                .select {
                    Group.course eq courseId
                }

        if (restrictedGroups.isNotEmpty()) {
            query.andWhere {
                Group.id inList restrictedGroups
            }
        }

        query.map {
            ReadGroupsController.GroupResp(
                    it[Group.id].value.toString(),
                    it[Group.name]
            )
        }
    }
}
