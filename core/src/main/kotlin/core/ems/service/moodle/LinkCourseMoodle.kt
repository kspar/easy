package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentMoodlePendingAccess
import core.db.StudentMoodlePendingCourseGroup
import core.ems.service.assertCourseExists
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.exception.ResourceLockedException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class MoodleLinkCourseController(
    val moodleStudentsSyncService: MoodleStudentsSyncService,
    val moodleGradesSyncService: MoodleGradesSyncService,
) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("moodle_props") val moodleProps: MoodleReq?,
        @param:JsonProperty("force") val force: Boolean = false,
    )

    data class MoodleReq(
        @param:JsonProperty("moodle_short_name") @field:NotBlank @field:Size(max = 500) val moodleShortName: String,
        @param:JsonProperty("sync_students") val syncStudents: Boolean,
        @param:JsonProperty("sync_grades") val syncGrades: Boolean,
    )

    @Secured("ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/moodle")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @Valid @RequestBody body: Req,
        caller: EasyUser
    ) {

        if (body.moodleProps == null) {
            log.info { "Unlinking course $courseIdStr from Moodle by ${caller.id} (force: ${body.force})" }
        } else {
            val moodleProps = body.moodleProps
            log.info {
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
            } catch (_: ResourceLockedException) {
                log.info { "Cannot change Moodle link, sync is in progress for course $courseId" }
                throw InvalidRequestException(
                    "Moodle sync is in progress", ReqError.MOODLE_SYNC_IN_PROGRESS, notify = false
                )
            }
        }
    }

    private fun linkCourse(courseId: Long, moodleProps: MoodleReq?) {
        transaction {
            val previousShortName = Course
                .select(Course.moodleShortName)
                .where { Course.id eq courseId }
                .single()[Course.moodleShortName]

            Course.update({ Course.id eq courseId }) {
                if (moodleProps == null) {
                    it[moodleShortName] = null
                } else {
                    it[moodleShortName] = moodleProps.moodleShortName
                    it[moodleSyncStudents] = moodleProps.syncStudents
                    it[moodleSyncGrades] = moodleProps.syncGrades
                }
            }

            // Unlinking, or **re-pointing at a different Moodle course**. The second case is the one
            // that is easy to miss and behaves identically: an invitation names a course, not a
            // Moodle course, so after `PROG-2026` becomes `PROG-2027` every invite minted for the
            // first still enrols its holder into a course now populated from the second. The short
            // name stays non-null, so the join's `isNotNull()` guard waves it through — nothing else
            // records which Moodle course an invite was issued under.
            //
            // Toggling only the sync flags leaves the short name alone and must therefore keep the
            // invitations, which is why this compares rather than firing on every write.
            if (moodleProps == null || previousShortName != moodleProps.moodleShortName) {
                // EZ-1780. Unlinking used to null the short name and stop there, leaving every
                // outstanding Moodle invitation in place — and they still worked, so "unlink" did
                // not stop Moodle-driven enrolment. The join is separately guarded now; this is the
                // other half, and it is the half that makes the state coherent: `moodle_linked:
                // false` with a populated `students_moodle_pending` is a combination nothing in the
                // UI was designed for, and it is what produced that.
                //
                // Deliberately destructive, and both client paths that reach it — the unlink button
                // and the short-name edit — say how many invitations it will drop, because that is
                // what makes it safe to be. Re-linking is not a rollback: the invite ids are gone,
                // but a re-sync regenerates a pending access for everyone still in the Moodle
                // course. The unrecoverable case is narrow — someone who was in the Moodle course
                // when the invites went out and is not when you re-link.
                //
                // Groups first, though nothing in the database forces the order: changeset 201021-2
                // dropped the foreign key from `student_moodle_pending_course_group_access` to the
                // access row, so `Tables.kt` declaring it a `reference` is a claim Postgres does not
                // enforce. Which is exactly why the order is written down rather than left to the
                // constraint — deleting the accesses first would silently strand the group rows,
                // keyed by a `moodle_username` with nothing left to resolve it against.
                val groupsDropped = StudentMoodlePendingCourseGroup.deleteWhere {
                    StudentMoodlePendingCourseGroup.course eq courseId
                }
                val invitesDropped = StudentMoodlePendingAccess.deleteWhere {
                    StudentMoodlePendingAccess.course eq courseId
                }
                val what =
                    if (moodleProps == null) "Unlinked course $courseId from Moodle"
                    else "Re-pointed course $courseId from Moodle course " +
                            "'$previousShortName' to '${moodleProps.moodleShortName}'"
                log.info {
                    "$what: dropped $invitesDropped pending invitation(s) and " +
                            "$groupsDropped pending group assignment(s)"
                }
            }
        }
    }
}
