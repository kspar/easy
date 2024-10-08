package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.canStudentAccessCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class AddStudentsToCourseGroupController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("active_students") @field:Valid val activeStudents: List<ActiveStudentReq> = emptyList(),
        @JsonProperty("pending_students") @field:Valid val pendingStudents: List<PendingStudentReq> = emptyList(),
        @JsonProperty("moodle_pending_students") @field:Valid val moodlePendingStudents: List<MoodlePendingStudentReq> = emptyList(),
    )

    data class ActiveStudentReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String,
    )

    data class PendingStudentReq(
        @JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String,
    )

    data class MoodlePendingStudentReq(
        @JsonProperty("moodle_username") @field:NotBlank @field:Size(max = 100) val moodleUsername: String,
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
        val pendingStudentEmails = body.pendingStudents.map { it.email }
        val moodlePendingStudentUnames = body.moodlePendingStudents.map { it.moodleUsername }

        log.info {
            "Add students $activeStudentIds, $pendingStudentEmails, $moodlePendingStudentUnames " +
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
        pendingStudentEmails.forEach { assertPendingStudentExistsOnCourse(it, courseId) }
        moodlePendingStudentUnames.forEach { assertMoodlePendingStudentExistsOnCourse(it, courseId) }

        addStudentsToGroup(courseId, groupId, activeStudentIds, pendingStudentEmails, moodlePendingStudentUnames)
    }


    private fun assertStudentExistsOnCourse(studentId: String, courseId: Long) {
        if (!canStudentAccessCourse(studentId, courseId))
            throw InvalidRequestException(
                "No student found with ID $studentId on course $courseId",
                ReqError.ENTITY_WITH_ID_NOT_FOUND
            )
    }

    private fun assertPendingStudentExistsOnCourse(email: String, courseId: Long) {
        if (!hasStudentPendingAccessToCourse(email, courseId))
            throw InvalidRequestException(
                "No pending student found with email $email on course $courseId",
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

    private fun hasStudentPendingAccessToCourse(studentEmail: String, courseId: Long): Boolean = transaction {
        StudentPendingAccess.selectAll().where {
            StudentPendingAccess.email.eq(studentEmail) and
                    StudentPendingAccess.course.eq(courseId)
        }.count() > 0
    }


    private fun hasStudentMoodlePendingAccessToCourse(moodleUsername: String, courseId: Long): Boolean = transaction {
        StudentMoodlePendingAccess.selectAll().where {
            StudentMoodlePendingAccess.moodleUsername.eq(moodleUsername) and
                    StudentMoodlePendingAccess.course.eq(courseId)
        }.count() > 0
    }


    private fun addStudentsToGroup(
        courseId: Long, groupId: Long, activeStudentIds: List<String>,
        pendingStudentEmails: List<String>, moodlePendingStudentUnames: List<String>
    ) {
        transaction {
            StudentCourseGroup.batchInsert(activeStudentIds, ignore = true) {
                this[StudentCourseGroup.student] = it
                this[StudentCourseGroup.course] = courseId
                this[StudentCourseGroup.courseGroup] = groupId
            }

            StudentPendingCourseGroup.batchInsert(pendingStudentEmails, ignore = true) {
                this[StudentPendingCourseGroup.email] = it
                this[StudentPendingCourseGroup.course] = courseId
                this[StudentPendingCourseGroup.courseGroup] = groupId
            }

            StudentMoodlePendingCourseGroup.batchInsert(moodlePendingStudentUnames, ignore = true) {
                this[StudentMoodlePendingCourseGroup.moodleUsername] = it
                this[StudentMoodlePendingCourseGroup.course] = courseId
                this[StudentMoodlePendingCourseGroup.courseGroup] = groupId
            }
        }
    }
}
