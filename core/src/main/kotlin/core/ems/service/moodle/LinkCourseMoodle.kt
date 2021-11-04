package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.assertCourseExists
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.exception.ResourceLockedException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class MoodleLinkCourseController(
    val moodleStudentsSyncService: MoodleStudentsSyncService,
    val moodleGradesSyncService: MoodleGradesSyncService,
) {

    data class Req(
        @JsonProperty("moodle_props") val moodleProps: MoodleReq?,
        @JsonProperty("force") val force: Boolean = false,
    )

    data class MoodleReq(
        @JsonProperty("moodle_short_name") @field:NotBlank @field:Size(max = 500) val moodleShortName: String,
        @JsonProperty("sync_students") val syncStudents: Boolean,
        @JsonProperty("sync_grades") val syncGrades: Boolean,
    )

    @Secured("ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/moodle")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ) {

        if (body.moodleProps == null) {
            log.debug { "Unlinking course $courseIdStr from Moodle by ${caller.id} (force: ${body.force})" }
        } else {
            val moodleProps = body.moodleProps
            log.debug {
                "Linking Moodle course ${moodleProps.moodleShortName} with course $courseIdStr by ${caller.id} " +
                        "(sync students: ${moodleProps.syncStudents}, sync grades: ${moodleProps.syncGrades}, force: ${body.force})"
            }
        }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        assertCourseExists(courseId)

        // Don't care about locks if force
        if (body.force) {
            linkCourse(courseId, body.moodleProps)

        } else {
            try {
                moodleStudentsSyncService.syncStudentsLock.with(courseId) {
                    moodleGradesSyncService.syncGradesLock.with(courseId) {
                        linkCourse(courseId, body.moodleProps)
                    }
                }
            } catch (e: ResourceLockedException) {
                log.info { "Cannot change Moodle link, sync is in progress for course $courseId" }
                throw InvalidRequestException(
                    "Moodle sync is in progress", ReqError.MOODLE_SYNC_IN_PROGRESS, notify = false
                )
            }
        }
    }

    private fun linkCourse(courseId: Long, moodleProps: MoodleReq?) {
        transaction {
            Course.update({ Course.id eq courseId }) {
                if (moodleProps == null) {
                    it[moodleShortName] = null
                } else {
                    it[moodleShortName] = moodleProps.moodleShortName
                    it[moodleSyncStudents] = moodleProps.syncStudents
                    it[moodleSyncGrades] = moodleProps.syncGrades
                }
            }
        }
    }
}
