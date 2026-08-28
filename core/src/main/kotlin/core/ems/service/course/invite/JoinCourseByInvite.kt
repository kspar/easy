package core.ems.service.course.invite

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.CourseInviteLink
import core.db.StudentCourseAccess
import core.exception.InvalidRequestException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class JoinCourseByInvite {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("course_id") val courseId: String
    )

    @Secured("ROLE_STUDENT")
    @PostMapping("/courses/join/{invite-id}")
    fun controller(@PathVariable("invite-id") inviteId: String, caller: EasyUser): Resp {
        log.info { "Joining course by invite $inviteId by ${caller.id}" }

        return joinByInvite(inviteId, caller.id)
    }

    private fun joinByInvite(inviteId: String, studentId: String): Resp = transaction {
        // Expiry, the cap and the id decided in one query. This is still the right early rejection —
        // it is just no longer the thing that *enforces* the cap; see the update below.
        //
        // notify = false: an invite id that is mistyped, expired or used up is an ordinary answer to
        // an ordinary request, and the default on InvalidRequestException is to email the sysadmin.
        // A link read out to a lecture room and clicked by the last few students after it filled would
        // have sent one mail per student.
        val row = (CourseInviteLink innerJoin Course)
            .select(Course.id, Course.moodleShortName)
            .where {
                (CourseInviteLink.inviteId.upperCase() eq inviteId.uppercase()) and
                        CourseInviteLink.expiresAt.greater(DateTime.now()) and
                        CourseInviteLink.usedCount.less(CourseInviteLink.allowedUses)
            }.singleOrNull() ?: throw InvalidRequestException("Invalid invite link", notify = false)

        if (row[Course.moodleShortName] != null) {
            throw InvalidRequestException("Invite links are not available for Moodle-synced courses")
        }

        val courseId = row[Course.id]


        // Before the reservation, and deliberately so. `insertIgnore` plus `insertedCount` is what
        // makes a student re-clicking their own link free, and reserving a use first would charge them
        // for every revisit — a cap of one would then lock the course the moment its one student
        // refreshed the page.
        val accessesAdded = StudentCourseAccess.insertIgnore {
            it[course] = courseId
            it[student] = studentId
            it[createdAt] = DateTime.now()
        }.insertedCount


        if (accessesAdded > 0) {
            // **The cap is enforced here, not by the select above.** The select's check was against a
            // snapshot: at READ COMMITTED, concurrent joins all read the same `used_count`, all passed,
            // all inserted their own access row and all incremented, so an `allowed_uses = 1` link
            // admitted however many students clicked it together — which, for a link read out to a
            // room, is the normal access pattern rather than a corner case.
            //
            // Repeating the predicate in the UPDATE closes it, because an UPDATE takes a row lock and
            // re-evaluates its WHERE against the *committed* row after waiting: exactly one of any
            // number of concurrent joins can move `used_count` from `allowed_uses - 1` to
            // `allowed_uses`, and the others match no rows. The affected-row count is therefore the
            // authoritative answer, and 0 means somebody else took the last use.
            //
            // Throwing rolls back the access insert with it, so a student who loses the race is not
            // left enrolled on a course whose invite had no room for them.
            val reserved = CourseInviteLink.update({
                CourseInviteLink.course eq courseId and
                        CourseInviteLink.usedCount.less(CourseInviteLink.allowedUses)
            }) {
                it.update(usedCount, usedCount + 1)
            }

            if (reserved == 0) throw InvalidRequestException("Invalid invite link", notify = false)
        }

        log.debug { "$studentId joined course $courseId by invite $inviteId" }
        Resp(courseId.toString())
    }
}

