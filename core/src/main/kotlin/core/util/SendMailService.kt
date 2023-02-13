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
        val subject = """Sind lisati Lahenduse kursusele "$courseTitle" """
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
        """.trimMargin()
        sendUserEmail(recipientEmail, subject, text)
    }

    @Async
    fun sendStudentAddedToCoursePending(courseTitle: String, recipientEmail: String) {
        val subject = """Sind lisati Lahenduse kursusele "$courseTitle" """
        val encodedEmail = URLEncoder.encode(recipientEmail, "UTF-8")
        val registerLink = "$wuiBaseUrl/link/register?email=$encodedEmail"
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
}