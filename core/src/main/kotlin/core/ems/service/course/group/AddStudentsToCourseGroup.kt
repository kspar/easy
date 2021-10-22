package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingCourseGroup
import core.db.StudentPendingCourseGroup
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

        log.debug { "Add students $activeStudentIds, $pendingStudentEmails, $moodlePendingStudentUnames " +
                "to group $groupIdStr on course $courseIdStr by ${caller.id}" }

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
        activeStudentIds.forEach { assertStudentExistsOnCourse(it, courseId) }
        pendingStudentEmails.forEach { assertPendingStudentExistsOnCourse(it, courseId) }
        moodlePendingStudentUnames.forEach { assertMoodlePendingStudentExistsOnCourse(it, courseId) }

        addStudentsToGroup(courseId, groupId, activeStudentIds, pendingStudentEmails, moodlePendingStudentUnames)
    }
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
