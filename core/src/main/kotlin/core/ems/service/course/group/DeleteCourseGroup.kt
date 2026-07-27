package core.ems.service.course.group

import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseGroup
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingCourseGroup
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        log.info { "Delete group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId)
        }
        assertGroupExistsOnCourse(groupId, courseId)

        deleteGroup(courseId, groupId)
    }

    private fun deleteGroup(courseId: Long, groupId: Long) {
        transaction {
            val students = StudentCourseGroup.selectAll().where { StudentCourseGroup.courseGroup eq groupId }.count()
            val moodlePendingStudents = StudentMoodlePendingCourseGroup.selectAll()
                .where { StudentMoodlePendingCourseGroup.courseGroup eq groupId }.count()

            if (students + moodlePendingStudents > 0) {
                throw InvalidRequestException(
                    "Cannot delete group $groupId since there are still $students students " +
                            "and $moodlePendingStudents Moodle pending students in this group.",
                    ReqError.GROUP_NOT_EMPTY
                )
            }

            CourseGroup.deleteWhere {
                CourseGroup.id eq groupId and (CourseGroup.course eq EntityID(courseId, Course))
            }
        }
    }
}