package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseGroup
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.getTeacherRestrictedCourseGroups
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
class ReadCourseGroupsController {

    data class Resp(
        @JsonProperty("groups") val groups: List<GroupResp>,
        @JsonProperty("self_is_restricted") val isRestricted: Boolean,
    )

    data class GroupResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/groups")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        caller: EasyUser
    ): Resp {

        log.debug { "Getting all groups on course $courseIdStr for ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val restrictedGroups = getTeacherRestrictedCourseGroups(courseId, caller)

        return Resp(selectGroups(courseId, restrictedGroups), restrictedGroups.isNotEmpty())
    }
}

private fun selectGroups(courseId: Long, restrictedGroups: List<Long>): List<ReadCourseGroupsController.GroupResp> {
    return transaction {
        val query = CourseGroup
            .select {
                CourseGroup.course eq courseId
            }

        if (restrictedGroups.isNotEmpty()) {
            query.andWhere {
                CourseGroup.id inList restrictedGroups
            }
        }

        query.map {
            ReadCourseGroupsController.GroupResp(
                it[CourseGroup.id].value.toString(),
                it[CourseGroup.name]
            )
        }
    }
}
