package core.ems.service.ai

import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * EZ-1712. Zero the course's token counter — a new semester, a new budget, a teacher who has
 * looked at the number and decided it is fine. Its own endpoint rather than a flag on the props
 * write, so that editing the budget can never reset the count by accident.
 *
 * The audit rows are untouched: what was spent is still in `ai_feedback`, only the running total
 * starts over, and `ai_tokens_reset_at` records from when.
 */
@RestController
@RequestMapping("/v2")
class ResetCourseAiUsageController {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/ai/reset-usage")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser) {
        val courseId = courseIdStr.idToLongOrInvalidReq()
        caller.assertAccess { teacherOnCourse(courseId) }

        val previous = transaction {
            val was = Course.select(Course.aiTokensUsed).where { Course.id eq courseId }.single()[Course.aiTokensUsed]
            Course.update({ Course.id eq courseId }) {
                it[aiTokensUsed] = 0
                it[aiTokensResetAt] = DateTime.now()
            }
            was
        }
        log.info { "AI token counter of course $courseId reset by ${caller.id} (was $previous)" }
    }
}
