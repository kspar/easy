package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingCourseGroup
import core.db.StudentPendingCourseGroup
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourseGroup
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveStudentsFromCourseGroupController {

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

    data class Resp(
        @JsonProperty("deleted_count") val deletedCount: Int
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @DeleteMapping("/courses/{courseId}/groups/{groupId}/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("groupId") groupIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ): Resp {

        val activeStudentIds = body.activeStudents.map { it.id }
        val pendingStudentEmails = body.pendingStudents.map { it.email }
        val moodlePendingStudentUnames = body.moodlePendingStudents.map { it.moodleUsername }

        log.debug {
            "Remove students $activeStudentIds, $pendingStudentEmails, $moodlePendingStudentUnames from group " +
                    "$groupIdStr on course $courseIdStr by ${caller.id}"
        }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertGroupExistsOnCourse(groupId, courseId)
        assertTeacherOrAdminHasAccessToCourseGroup(caller, courseId, groupId)

        val deletedCount = removeStudentsFromGroup(
            courseId, groupId, activeStudentIds, pendingStudentEmails, moodlePendingStudentUnames
        )
        return Resp(deletedCount)
    }
}

private fun removeStudentsFromGroup(
    courseId: Long, groupId: Long, activeStudentIds: List<String>,
    pendingStudentEmails: List<String>, moodlePendingStudentUnames: List<String>
): Int {
    return transaction {
        val deletedActive = StudentCourseGroup.deleteWhere {
            StudentCourseGroup.student.inList(activeStudentIds) and
                    StudentCourseGroup.courseGroup.eq(groupId) and
                    StudentCourseGroup.course.eq(courseId)
        }

        val deletedPending = StudentPendingCourseGroup.deleteWhere {
            StudentPendingCourseGroup.email.inList(pendingStudentEmails) and
                    StudentPendingCourseGroup.courseGroup.eq(groupId) and
                    StudentPendingCourseGroup.course.eq(courseId)
        }

        val deletedMoodlePending = StudentMoodlePendingCourseGroup.deleteWhere {
            StudentMoodlePendingCourseGroup.moodleUsername.inList(moodlePendingStudentUnames) and
                    StudentMoodlePendingCourseGroup.courseGroup.eq(groupId) and
                    StudentMoodlePendingCourseGroup.course.eq(courseId)
        }
        deletedActive + deletedPending + deletedMoodlePending
    }
}
