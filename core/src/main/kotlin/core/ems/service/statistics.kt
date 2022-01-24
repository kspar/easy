package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.cache.CachingService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentLinkedQueue
import javax.validation.Valid

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StatisticsController(private val statisticsService: StatisticsService) {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN", "ROLE_STUDENT")
    @PostMapping("/statistics/common")
    fun controller(@Valid @RequestBody dto: StatResp?, caller: EasyUser): StatResp {
        log.debug { "${caller.id} is querying statistics." }
        return runBlocking { statisticsService.getOrWaitStatUpdate(dto) }
    }
}

data class StatResp(
    @JsonProperty("in_auto_assessing") val inAutoAssessing: Long,
    @JsonProperty("total_submissions") val totalSubmissions: Long,
    @JsonProperty("total_users") val totalUsers: Long
)


@Service
class StatisticsService(private val cachingService: CachingService) {
    private val clientsListening = ConcurrentLinkedQueue<Channel<StatResp>>()
    private var lastKnownResponse = composeStatResp()

    suspend fun getOrWaitStatUpdate(currentStatResp: StatResp?): StatResp {
        val channel = Channel<StatResp>(Channel.CONFLATED)

        synchronized(this) {
            // if client has no current stats or stats differ, return latest queried stats...
            if (currentStatResp != lastKnownResponse) return lastKnownResponse else clientsListening.add(channel)
        }
        // else wait for update...
        return channel.receive()
    }


    @Scheduled(fixedDelayString = "\${easy.core.statistics.fixed-delay.ms}")
    fun pushStatUpdate() {
        synchronized(this) {
            val databaseState = composeStatResp()

            // If nothing new to push, don't push, just return
            if (databaseState == lastKnownResponse) return

            // Has new state, update and push
            lastKnownResponse = databaseState
            // Push response to clients
            while (clientsListening.isNotEmpty()) clientsListening.poll().trySend(lastKnownResponse)
        }
    }

    private fun composeStatResp() = StatResp(
        cachingService.countSubmissionsInAutoAssessment(),
        cachingService.countSubmissions(),
        cachingService.countTotalUsers()
    )
}