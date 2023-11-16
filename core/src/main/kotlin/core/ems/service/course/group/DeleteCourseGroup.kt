package core.ems.service.course.group

import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class DeleteCourseGroupController {
    private val log = KotlinLogging.logger {}

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

        caller.assertAccess {
            teacherOnCourse(courseId, false)
        }
        assertGroupExistsOnCourse(groupId, courseId)

        deleteGroup(courseId, groupId)
    }

    private fun deleteGroup(courseId: Long, groupId: Long) {
        transaction {
            val teachers = TeacherCourseGroup.select {
                TeacherCourseGroup.courseGroup eq groupId
            }.count()
            val students = StudentCourseGroup.select {
                StudentCourseGroup.courseGroup eq groupId
            }.count()
            val pendingStudents = StudentPendingCourseGroup.select {
                StudentPendingCourseGroup.courseGroup eq groupId
            }.count()
            val moodlePendingStudents = StudentMoodlePendingCourseGroup.select {
                StudentMoodlePendingCourseGroup.courseGroup eq groupId
            }.count()

            if (teachers + students + pendingStudents + moodlePendingStudents > 0) {
                throw InvalidRequestException(
                    "Cannot delete group $groupId since there are still $teachers teachers, $students students, " +
                            "$pendingStudents pending students and $moodlePendingStudents Moodle pending students in this group.",
                    ReqError.GROUP_NOT_EMPTY
                )
            }

            CourseGroup.deleteWhere {
                CourseGroup.id eq groupId and (CourseGroup.course eq EntityID(courseId, Course))
            }
        }
    }
}