package ee.urgas.ems.util

import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

private val log = KotlinLogging.logger {}

@Service
class SendMailService(private val mailSender: JavaMailSender) {

    @Value("\${easy.ems.mail.sys.from}")
    private lateinit var fromAddress: String
    @Value("\${easy.ems.mail.sys.to}")
    private lateinit var toSysAddress: String

    @Async
    fun sendSystemNotification(message: String) {
        val time = Instant.now().toString()
        val messageId = UUID.randomUUID().toString()
        log.info("Sending notification $messageId ($time) from $fromAddress to $toSysAddress")

        val bodyText = "$time\n$messageId\n\n$message"

        val mailMessage = SimpleMailMessage()
        mailMessage.setSubject("Easy:ems system notification $messageId")
        mailMessage.setText(bodyText)
        mailMessage.setTo(toSysAddress)
        mailMessage.setFrom(fromAddress)
        mailSender.send(mailMessage)

        log.info("Sent notification $messageId")
    }
}