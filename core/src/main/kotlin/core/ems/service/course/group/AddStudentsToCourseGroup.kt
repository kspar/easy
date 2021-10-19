package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseGroup
import core.ems.service.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddStudentsToCourseGroupController {

    data class Req(
        @JsonProperty("active_students") @field:Valid val activeStudents: List<ActiveStudentReq>,
        @JsonProperty("pending_students") @field:Valid val pendingStudents: List<PendingStudentReq>,
        @JsonProperty("moodle_pending_students") @field:Valid val moodlePendingStudents: List<MoodlePendingStudentReq>,
    )

    data class ActiveStudentReq(
        @JsonProperty("id") @field:NotBlank @field:Size(max = 100) val id: String,
    )

    data class PendingStudentReq(
        @JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String,
    )

    data class MoodlePendingStudentReq(
        @JsonProperty("moodleUsername") @field:NotBlank @field:Size(max = 100) val moodleUsername: String,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/groups/{groupId}/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("groupId") groupIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ) {
        log.debug { "Add students $body to group $groupIdStr on course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        /*
        A teacher can add students only to their own restricted groups, or any group if they don't have any restricted.
         */
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)
        assertTeacherOrAdminHasAccessToCourseGroup(caller, courseId, groupId)

        /*
        Students must be on the course already.
         */
        val activeStudentIds = body.activeStudents.map {
            if (!canStudentAccessCourse(it.id, courseId))
                throw InvalidRequestException(
                    "No student found with ID ${it.id} on course $courseId",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )

            it.id
        }
        val pendingStudentEmails = body.pendingStudents.map {
            if (!hasStudentPendingAccessToCourse(it.email, courseId))
                throw InvalidRequestException(
                    "No pending student found with email ${it.email} on course $courseId",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )

            it.email
        }
        val moodlePendingStudentUnames = body.moodlePendingStudents.map {
            if (!hasStudentMoodlePendingAccessToCourse(it.moodleUsername, courseId))
                throw InvalidRequestException(
                    "No Moodle pending student found with Moodle username ${it.moodleUsername} on course $courseId",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND
                )

            it.moodleUsername
        }

        addStudentsToGroup(courseId, groupId, activeStudentIds, pendingStudentEmails, moodlePendingStudentUnames)
    }
}

private fun addStudentsToGroup(
    courseId: Long, groupId: Long, activeStudentIds: List<String>,
    pendingStudentEmails: List<String>, moodlePendingStudentUnames: List<String>
) {

    transaction {

        StudentCourseGroup.batchInsert(activeStudentIds) {
            this[StudentCourseGroup.student] = it
            this[StudentCourseGroup.course] = courseId
            this[StudentCourseGroup.courseGroup] = groupId
        }

        // TODO: pending and moodlePending
        // TODO: waiting for EZ-1354

    }
}
