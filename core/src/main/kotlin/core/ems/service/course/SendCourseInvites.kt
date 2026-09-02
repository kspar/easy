package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.Course
import core.db.StudentCourseAccess
import core.db.StudentMoodlePendingAccess
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.normaliseEmail
import core.util.SendMailService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class SendCourseInvites(val sendMailService: SendMailService) {
    private val log = KotlinLogging.logger {}

    data class Req(@param:JsonProperty("emails") @field:Valid val students: List<StudentEmailReq>)

    data class StudentEmailReq(
        @param:JsonProperty("email") @field:NotBlank @field:Size(max = 100) val email: String,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/students/invite")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @RequestBody @Valid body: Req, caller: EasyUser
    ) {
        val courseId = courseIdStr.idToLongOrInvalidReq()
        // Normalised once, here, and used for the lookup *and* the send (EZ-1863). Two reasons it is
        // not enough to normalise inside the filters:
        //
        // - Deduplicated on the normalised form, as `AddTeachersToCourse` is. Once the lookup stops
        //   caring about case, `ann@…` and `Ann@…` in one paste are one student who matches twice
        //   and receives two identical invitations.
        // - The mail is addressed with this string too. `sendUserEmail` hands it to
        //   `SimpleMailMessage.setTo`, which an untrimmed address can make throw, and the
        //   pending-student mail tells its reader to register with *exactly* this address — so it
        //   had better be the address the course will match, not the spelling someone pasted.
        val emails = body.students.map { normaliseEmail(it.email) }.distinct()

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
            // Columns lowered as well as the input, for the reason `getUsernameByEmail` gives.
            //
            // This site failed more quietly than the one EZ-1863 was reported from: a capitalised
            // address matched neither filter, so it dropped out of both lists and the teacher got a
            // success with no mail sent and nothing naming the address that had gone nowhere.
            val pendingEmails = emails.filter {
                val existsMoodlePending = StudentMoodlePendingAccess.selectAll()
                    .where {
                        StudentMoodlePendingAccess.email.lowerCase().eq(it) and
                                StudentMoodlePendingAccess.course.eq(courseId)
                    }
                    .count() == 1L
                existsMoodlePending
            }
            val activeEmails = emails.filter {
                (StudentCourseAccess innerJoin Account).selectAll()
                    .where { Account.email.lowerCase().eq(it) and StudentCourseAccess.course.eq(courseId) }
                    .count() == 1L
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
