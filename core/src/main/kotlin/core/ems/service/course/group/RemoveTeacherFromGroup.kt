package core.ems.service.course.group

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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveTeacherFromGroupController {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/groups/{groupId}/teachers/{teacherId}")
    fun controller(
            @PathVariable("courseId") courseIdStr: String,
            @PathVariable("groupId") groupIdStr: String,
            @PathVariable("teacherId") teacherId: String,
            caller: EasyUser
    ) {
        log.debug { "Remove teacher $teacherId from group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)

        removeTeacherFromGroup(courseId, groupId, teacherId)
    }
}

private fun removeTeacherFromGroup(courseId: Long, groupId: Long, teacherUsername: String) {
    transaction {
        TeacherCourseGroup.deleteWhere {
            TeacherCourseGroup.teacher eq teacherUsername and
                    (TeacherCourseGroup.courseGroup eq groupId) and
                    (TeacherCourseGroup.course eq courseId)
        }
    }
}
