package core.ems.service.course.group

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.StudentCourseGroup
import core.db.StudentMoodlePendingCourseGroup
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.idToLongOrInvalidReq
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/v2")
class RemoveStudentsFromCourseGroupController {
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

    data class Resp(
        @get:JsonProperty("deleted_count") val deletedCount: Int
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
        val moodlePendingStudentUnames = body.moodlePendingStudents.map { it.moodleUsername }

        log.info {
            "Remove students $activeStudentIds, $moodlePendingStudentUnames from group " +
                    "$groupIdStr on course $courseIdStr by ${caller.id}"
        }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdStr.idToLongOrInvalidReq()

        caller.assertAccess {
            teacherOnCourse(courseId)
            assertGroupExistsOnCourse(groupId, courseId)
        }

        val deletedCount = removeStudentsFromGroup(
            courseId, groupId, activeStudentIds, moodlePendingStudentUnames
        )
        return Resp(deletedCount)
    }

    private fun removeStudentsFromGroup(
        courseId: Long, groupId: Long, activeStudentIds: List<String>,
        moodlePendingStudentUnames: List<String>
    ): Int = transaction {
        val deletedActive = StudentCourseGroup.deleteWhere {
            StudentCourseGroup.student.inList(activeStudentIds) and
                    StudentCourseGroup.courseGroup.eq(groupId) and
                    StudentCourseGroup.course.eq(courseId)
        }

        val deletedMoodlePending = StudentMoodlePendingCourseGroup.deleteWhere {
            StudentMoodlePendingCourseGroup.moodleUsername.inList(moodlePendingStudentUnames) and
                    StudentMoodlePendingCourseGroup.courseGroup.eq(groupId) and
                    StudentMoodlePendingCourseGroup.course.eq(courseId)
        }
        deletedActive + deletedMoodlePending
    }
}
