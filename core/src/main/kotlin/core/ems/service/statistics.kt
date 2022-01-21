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

        return runBlocking {
            when (dto) {
                // If client stats matches current known-stats, wait for update...
                statisticsService.getLastKnownStats() -> statisticsService.waitStatUpdate()
                // if client has no current stats or stats differ, return latest queried stats...
                else -> statisticsService.getLastKnownStats()
            }
        }
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

    fun getLastKnownStats() = lastKnownResponse

    suspend fun waitStatUpdate() = Channel<StatResp>(Channel.CONFLATED).also { clientsListening.add(it) }.receive()

    @Scheduled(fixedDelayString = "\${easy.core.statistics.fixed-delay.ms}")
    fun pushStatUpdate() {
        lastKnownResponse = composeStatResp()
        // Push response to clients
        while (clientsListening.isNotEmpty()) clientsListening.poll().trySend(lastKnownResponse)
    }

    private fun composeStatResp() = StatResp(
        cachingService.countSubmissionsInAutoAssessment(),
        cachingService.countSubmissions(),
        cachingService.countTotalUsers()
    )
}