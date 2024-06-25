package core.util

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.net.URLEncoder
import java.time.Instant
import java.util.*

private val log = KotlinLogging.logger {}

@Service
class SendMailService(private val mailSender: JavaMailSender) {

    @Value("\${easy.core.mail.from}")
    private lateinit var fromAddress: String

    @Value("\${easy.core.mail.user.enabled}")
    private var userEmailEnabled: Boolean = true

    @Value("\${easy.core.mail.sys.enabled}")
    private var sysEmailEnabled: Boolean = true

    @Value("\${easy.core.mail.sys.to}")
    private lateinit var toSysAddress: String

    @Value("\${easy.wui.base-url}")
    private lateinit var wuiBaseUrl: String

    @Async
    fun sendStudentAddedToCourseActive(courseTitle: String, recipientEmail: String) {
        val subject =
            """Sind lisati Lahenduse kursusele "$courseTitle" / You were added to course $courseTitle in Lahendus"""
        val text = """
            |Tere!
            |
            |Sind lisati meiliaadressiga $recipientEmail kursusele "$courseTitle" Lahenduse keskkonnas.
            |
            |Kursusele ligi pääsemiseks mine $wuiBaseUrl ja logi sisse meiliaadressiga $recipientEmail.
            |
            |Kui sul on parool meelest läinud, siis proovi "Unustasid parooli?" funktsionaalsust sisselogimise lehel.
            | 
            |Kui sa pärast sisse logimist seda kursust ei näe, siis veendu, et kasutaja meiliaadress oleks $recipientEmail. 
            |
            |Mõnusat progemist!
            |Lahenduse meeskond
            |
            |
            |--------------
            |
            |Hey!
            |
            |You were added to the course $courseTitle in Lahendus by your email address $recipientEmail.
            |
            |To access the course, go to $wuiBaseUrl and log in using the email address $recipientEmail.
            |
            |If you've forgotten your password, then feel free to use the Forgot password? feature on the login page.
            |
            |If you can't see the course after logging in, make sure your account's email address is $recipientEmail.
            |
            |Happy coding!
            |The Lahendus Team
            |
        """.trimMargin()
        sendUserEmail(recipientEmail, subject, text)
    }

    @Async
    fun sendStudentAddedToCoursePending(courseTitle: String, recipientEmail: String) {
        val subject =
            """Sind lisati Lahenduse kursusele "$courseTitle" / You were added to course $courseTitle in Lahendus"""
        val encodedEmail = URLEncoder.encode(recipientEmail, "UTF-8")
        val registerLink = "$wuiBaseUrl/register?email=$encodedEmail"
        val text = """
            |Tere!
            |
            |Sind lisati meiliaadressiga $recipientEmail kursusele "$courseTitle" Lahenduse keskkonnas.
            |
            |Meile tundub, et selle meiliaadressiga kasutajat Lahenduses veel ei eksisteeri. Kursusele ligi pääsemiseks klõpsa järgneval lingil ja loo endale kasutaja:
            |  $registerLink
            |
            |Kui sul on juba Lahenduse kasutaja olemas, siis veendu, et selle meiliaadress oleks $recipientEmail. Vajadusel saad meiliaadressi muuta konto seadete lehel. Kui meiliaadress on õigeks muudetud, siis peaksid samuti automaatselt kursusele ligi saama.
            | 
            |Kui selle meiliaadressiga kasutaja on juba loodud, siis logi sellega sisse - siis peaksid kohe kursusele ligi saama. Kui parool on meelest läinud, siis proovi "Unustasid parooli?" funktsionaalsust sisselogimise lehel.
            |
            |Mõnusat progemist!
            |Lahenduse meeskond
            |
            |
            |--------------
            |
            |Hey!
            |
            |You were added to the course $courseTitle in Lahendus by your email address $recipientEmail.
            |
            |We think that there's no account in Lahendus with this email address. To access the course, click on the following link and create an account:
            |  $registerLink
            |  
            |If you already have an account at Lahendus, then make sure its email address is $recipientEmail. You can change the email address in your account settings. Once you've changed the email address to $recipientEmail, you should automatically get access to this course.
            | 
            |If you already have an account with this email address, then simply log in and you should automatically get access to the course. If you've forgotten your password, then feel free to use the Forgot password? feature on the login page. 
            |
            |Happy coding!
            |The Lahendus Team
            |
        """.trimMargin()
        sendUserEmail(recipientEmail, subject, text)
    }

    @Async
    fun sendStudentGotNewTeacherFeedback(
        courseId: Long, courseExId: Long,
        exerciseTitle: String, courseTitle: String,
        feedback: String, recipientEmail: String
    ) {
        val subject =
            """Uus kommentaar ($exerciseTitle) / New feedback comment ($exerciseTitle)"""
        val exerciseLink = "$wuiBaseUrl/courses/$courseId/exercises/$courseExId/"
        val text = """
            |Tere!
            |
            |Sinu ülesande "$exerciseTitle" esitusele kursusel "$courseTitle" lisati tagasiside kommentaar:
            |
            |$feedback
            |
            |Vaata oma esitust ja tagasisidet siin: $exerciseLink
            |
            |
            |--------------
            |
            |Hey!
            |
            |Your submission for exercise $exerciseTitle on course $courseTitle received a new feedback comment:
            |
            |$feedback
            |
            |See your submission and the feedback here: $exerciseLink
            |
            """.trimMargin()
        sendUserEmail(recipientEmail, subject, text)
    }

    @Async
    fun sendUserEmail(recipientEmail: String, subject: String, message: String) {
        if (!userEmailEnabled) {
            log.info { "User email disabled, ignoring" }
            log.info { "Message: $message" }
            return
        }

        val mailMessage = SimpleMailMessage()
        mailMessage.setSubject(subject)
        mailMessage.setText(message)
        mailMessage.setTo(recipientEmail)
        mailMessage.setFrom(fromAddress)

        mailSender.send(mailMessage)
    }

    @Async
    fun sendSystemNotification(message: String, id: String = UUID.randomUUID().toString()) {
        if (!sysEmailEnabled) {
            log.info { "System notifications disabled, ignoring" }
            log.info { "Message: $message" }
            return
        }

        val time = Instant.now().toString()
        log.info("Sending notification $id ($time) from $fromAddress to $toSysAddress")

        val bodyText = "$message\n\n$id\n$time"

        val mailMessage = SimpleMailMessage()
        mailMessage.setSubject("Easy:core system notification $id")
        mailMessage.setText(bodyText)
        mailMessage.setTo(toSysAddress)
        mailMessage.setFrom(fromAddress)
        mailSender.send(mailMessage)

        log.info("Sent notification $id")
    }

    fun sendStudentInvitedToMoodleLinkedCourse(
        courseTitle: String,
        inviteId: String,
        moodleUsername: String,
        recipientEmail: String
    ) {
        val subject =
            """Sind lisati Lahenduse kursusele "$courseTitle" / You were added to course $courseTitle in Lahendus"""
        val registerLink = "$wuiBaseUrl/courses/moodle/join/$inviteId"
        val text = """
            |Tere!
            |
            |Sind lisati Moodle kasutajanimega $moodleUsername kursusele "$courseTitle" Lahenduse keskkonnas.
            |
            |Kursusele ligi pääsemiseks klõpsa järgneval lingil:
            |  $registerLink
            |            
            |
            |Mõnusat progemist!
            |Lahenduse meeskond
            |
            |
            |--------------
            |
            |Hey!
            |
            |You were added to the course $courseTitle in Lahendus by your Moodle account $moodleUsername.
            |
            | To access the course, click on the following link:
            |  $registerLink
            |  
            |Happy coding!
            |The Lahendus Team
            |
        """.trimMargin()
        sendUserEmail(recipientEmail, subject, text)
    }
}