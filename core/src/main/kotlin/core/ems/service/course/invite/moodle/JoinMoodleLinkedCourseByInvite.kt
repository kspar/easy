package core.ems.service.course.invite.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.singleOrInvalidRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.upperCase
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class JoinMoodleLinkedCourseByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(@get:JsonProperty("course_id") val courseId: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/courses/moodle/join/{inviteId}")
    fun controller(@PathVariable("inviteId") inviteId: String, caller: EasyUser): Resp {
        log.info { "Joining Moodle course by invite $inviteId by ${caller.id}" }

        return join(inviteId, caller.id)
    }

    private fun join(inviteId: String, studentId: String): Resp = transaction {
        val (courseId, moodleUsername) = (StudentMoodlePendingAccess innerJoin Course)
            .select(Course.id, StudentMoodlePendingAccess.moodleUsername)
            .where {
                (StudentMoodlePendingAccess.inviteId.upperCase() eq inviteId.uppercase()) and
                        // EZ-1780. Without this, an invite outlived the link: unlinking a course set
                        // `moodle_short_name = null` and deleted nothing, so an outstanding invite
                        // still enrolled its holder — with a `moodle_username` recorded and their
                        // pending group assignments copied across — into a course with no Moodle link
                        // at all. A teacher who unlinked to stop Moodle enrolment had not stopped it.
                        //
                        // Unlink now deletes the pending rows too (`LinkCourseMoodle`), so in a
                        // consistent database this predicate matches nothing extra. It is here
                        // because the two must both hold and only one of them is a cleanup: rows can
                        // predate the fix, and `JoinCourseByInvite` carries the mirror check for the
                        // same reason — a plain invite is refused on a Moodle-linked course.
                        Course.moodleShortName.isNotNull()
            }.map { it[Course.id] to it[StudentMoodlePendingAccess.moodleUsername] }
            .singleOrInvalidRequest(false)

        StudentCourseAccess.insertIgnore {
            it[course] = courseId
            it[student] = studentId
            it[createdAt] = DateTime.now()
            it[StudentCourseAccess.moodleUsername] = moodleUsername
        }

        StudentMoodlePendingCourseGroup
            .select(StudentMoodlePendingCourseGroup.courseGroup)
            .where { (StudentMoodlePendingCourseGroup.course eq courseId) and (StudentMoodlePendingCourseGroup.moodleUsername eq moodleUsername) }
            .map { it[StudentMoodlePendingCourseGroup.courseGroup] }
            .forEach { group ->
                // insertIgnore, matching the access insert above rather than differing from it two
                // lines later. `StudentCourseGroup`'s key is (course, student, group), so a student
                // who is *already* in a group this invite also names made a plain insert violate it
                // and the whole request fail with a 500.
                //
                // That sequence is ordinary: the Moodle sync issues a personal invite naming group G,
                // a teacher then adds the student to the course and to G by hand — which does not
                // delete the pending row — and the student clicks the link in their email afterwards.
                // The rollback meant the invite was not consumed, so the failure was safe and also
                // permanent: nothing cleared the pending row, so every retry failed the same way.
                //
                // Already being in the group is the outcome this loop wants, so meeting it is not an
                // error.
                StudentCourseGroup.insertIgnore {
                    it[StudentCourseGroup.student] = studentId
                    it[StudentCourseGroup.course] = courseId
                    it[StudentCourseGroup.courseGroup] = group
                }
            }

        // Must match the lookup above, or the pending access outlives the join
        StudentMoodlePendingAccess.deleteWhere {
            StudentMoodlePendingAccess.inviteId.upperCase() eq inviteId.uppercase()
        }

        // And the group assignments with it, which the line above did not do. They were copied into
        // real `student_course_group_access` rows a few lines up, so what is left is a pending
        // assignment for somebody who is no longer pending: `DeleteCourseGroup` counts it when it
        // warns how many people a group deletion affects, and the group-membership endpoints will
        // happily operate on it. Self-healing, in that the next Moodle sync clears every pending
        // group row for the course and rewrites them — which is why this was invisible, and why it
        // is still worth doing rather than relying on a cron to tidy up after a request.
        StudentMoodlePendingCourseGroup.deleteWhere {
            (StudentMoodlePendingCourseGroup.course eq courseId) and
                    (StudentMoodlePendingCourseGroup.moodleUsername eq moodleUsername)
        }

        log.debug { "$studentId joined Moodle linked course $courseId by invite $inviteId" }
        Resp(courseId.toString())
    }
}

