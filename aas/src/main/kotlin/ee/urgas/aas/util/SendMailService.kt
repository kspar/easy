package ee.urgas.aas.util

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

    @Value("\${easy.aas.mail.sys.from}")
    private lateinit var fromAddress: String
    @Value("\${easy.aas.mail.sys.to}")
    private lateinit var toSysAddress: String
    @Value("\${easy.aas.mail.sys.enabled}")
    private var enabled: Boolean = true

    @Async
    fun sendSystemNotification(message: String) {
        if (!enabled) {
            log.info { "System notifications disabled, ignoring" }
            return
        }

        val time = Instant.now().toString()
        val messageId = UUID.randomUUID().toString()
        log.info("Sending notification $messageId ($time) from $fromAddress to $toSysAddress")

        val bodyText = "$time\n$messageId\n\n$message"

        val mailMessage = SimpleMailMessage()
        mailMessage.setSubject("Easy:aas system notification $messageId")
        mailMessage.setText(bodyText)
        mailMessage.setTo(toSysAddress)
        mailMessage.setFrom(fromAddress)
        mailSender.send(mailMessage)

        log.info("Sent notification $messageId")
    }
}