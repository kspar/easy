package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class SendCourseInvites(val sendMailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("emails") @field:Valid val students: List<StudentEmailReq>)

    data class StudentEmailReq(
        @JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/students/invite")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @RequestBody @Valid body: Req, caller: EasyUser
    ) {
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val emails = body.students.map { it.email }

        log.info { "Sending email invites to students $emails on course $courseId by ${caller.id}" }

        caller.assertAccess {
            teacherOnCourse(courseId)
        }

        sendEmails(courseId, emails)
    }


    private fun sendEmails(courseId: Long, emails: List<String>) {

        val courseTitle = transaction {
            Course.selectAll().where { Course.id.eq(courseId) }.map {
                it[Course.alias] ?: it[Course.title]
            }.single()
        }

        transaction {
            val pendingEmails = emails.filter {
                val existsPending = StudentPendingAccess.selectAll()
                    .where { StudentPendingAccess.email.eq(it) and StudentPendingAccess.course.eq(courseId) }
                    .count() == 1L
                val existsMoodlePending = StudentMoodlePendingAccess.selectAll()
                    .where { StudentMoodlePendingAccess.email.eq(it) and StudentMoodlePendingAccess.course.eq(courseId) }
                    .count() == 1L
                existsPending || existsMoodlePending
            }
            val activeEmails = emails.filter {
                (StudentCourseAccess innerJoin Account).selectAll()
                    .where { Account.email.eq(it) and StudentCourseAccess.course.eq(courseId) }.count() == 1L
            }

            log.debug { "Sending email invites to pending students: $pendingEmails" }
            log.debug { "Sending email invites to active students: $activeEmails" }

            pendingEmails.forEach {
                sendMailService.sendStudentAddedToCoursePending(courseTitle, it)
            }
            activeEmails.forEach {
                sendMailService.sendStudentAddedToCourseActive(courseTitle, it)
            }
        }
    }
}

