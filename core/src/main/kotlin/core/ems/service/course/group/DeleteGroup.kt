package core.ems.service.course.group

import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class DeleteGroupController {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/groups/{groupId}")
    fun controller(
            @PathVariable("courseId") courseIdStr: String,
            @PathVariable("groupId") groupIdStr: String,
            caller: EasyUser
    ) {
        log.debug { "Delete group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)

        deleteGroup(courseId, groupId)
    }
}


private fun deleteGroup(courseId: Long, groupId: Long) {
    transaction {
        val teachers = TeacherGroupAccess.select {
            TeacherGroupAccess.group eq groupId
        }.count()
        val students = StudentGroupAccess.select {
            StudentGroupAccess.group eq groupId
        }.count()
        val pendingStudents = StudentPendingGroup.select {
            StudentPendingGroup.group eq groupId
        }.count()
        val moodlePendingStudents = StudentMoodlePendingGroup.select {
            StudentMoodlePendingGroup.group eq groupId
        }.count()

        if (teachers + students + pendingStudents + moodlePendingStudents > 0) {
            throw InvalidRequestException(
                    "Cannot delete group $groupId since there are still $teachers teachers, $students students, " +
                            "$pendingStudents pending students and $moodlePendingStudents Moodle pending students in this group.",
                    ReqError.GROUP_NOT_EMPTY
            )
        }

        Group.deleteWhere {
            Group.id eq groupId and (Group.course eq EntityID(courseId, Course))
        }
    }
}