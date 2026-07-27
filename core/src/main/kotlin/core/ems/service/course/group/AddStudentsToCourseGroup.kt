package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingAccess
import core.db.StudentMoodlePendingCourseGroup
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.canStudentAccessCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class AddStudentsToCourseGroupController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("active_students") @field:Valid val activeStudents: List<ActiveStudentReq> = emptyList(),
        @param:JsonProperty("moodle_pending_students") @field:Valid val moodlePendingStudents: List<MoodlePendingStudentReq> = emptyList(),
    )

    data class ActiveStudentReq(
        @param:JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String,
    )

    data class MoodlePendingStudentReq(
        @param:JsonProperty("moodle_username") @field:NotBlank @field:Size(max = 100) val moodleUsername: String,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/groups/{groupId}/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("groupId") groupIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ) {
        val activeStudentIds = body.activeStudents.map { it.id }
        val moodlePendingStudentUnames = body.moodlePendingStudents.map { it.moodleUsername }

        log.info {
            "Add students $activeStudentIds, $moodlePendingStudentUnames " +
                    "to group $groupIdStr on course $courseIdStr by ${caller.id}"
        }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()


        caller.assertAccess {
            teacherOnCourse(courseId)
            assertGroupExistsOnCourse(groupId, courseId)
        }

        /*
        Students must be on the course already.
         */
        activeStudentIds.forEach { assertStudentExistsOnCourse(it, courseId) }
        moodlePendingStudentUnames.forEach { assertMoodlePendingStudentExistsOnCourse(it, courseId) }

        addStudentsToGroup(courseId, groupId, activeStudentIds, moodlePendingStudentUnames)
    }


    private fun assertStudentExistsOnCourse(studentId: String, courseId: Long) {
        if (!canStudentAccessCourse(studentId, courseId))
            throw InvalidRequestException(
                "No student found with ID $studentId on course $courseId",
                ReqError.ENTITY_WITH_ID_NOT_FOUND
            )
    }

    private fun assertMoodlePendingStudentExistsOnCourse(moodleUsername: String, courseId: Long) {
        if (!hasStudentMoodlePendingAccessToCourse(moodleUsername, courseId))
            throw InvalidRequestException(
                "No Moodle pending student found with Moodle username $moodleUsername on course $courseId",
                ReqError.ENTITY_WITH_ID_NOT_FOUND
            )
    }

    private fun hasStudentMoodlePendingAccessToCourse(moodleUsername: String, courseId: Long): Boolean = transaction {
        StudentMoodlePendingAccess.selectAll().where {
            StudentMoodlePendingAccess.moodleUsername.eq(moodleUsername) and
                    StudentMoodlePendingAccess.course.eq(courseId)
        }.count() > 0
    }


    private fun addStudentsToGroup(
        courseId: Long, groupId: Long, activeStudentIds: List<String>,
        moodlePendingStudentUnames: List<String>
    ) {
        transaction {
            StudentCourseGroup.batchInsert(activeStudentIds, ignore = true) {
                this[StudentCourseGroup.student] = it
                this[StudentCourseGroup.course] = courseId
                this[StudentCourseGroup.courseGroup] = groupId
            }

            StudentMoodlePendingCourseGroup.batchInsert(moodlePendingStudentUnames, ignore = true) {
                this[StudentMoodlePendingCourseGroup.moodleUsername] = it
                this[StudentMoodlePendingCourseGroup.course] = courseId
                this[StudentMoodlePendingCourseGroup.courseGroup] = groupId
            }
        }
    }
}
