package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.AutoGradeStatus
import core.db.Submission
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.cache.annotation.Cacheable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.DeferredResult
import java.util.concurrent.ForkJoinPool
import javax.validation.Valid

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StatisticsController(private val statisticsService: StatisticsService) {


    data class ReqResp(@JsonProperty("in_auto_assessing") val inAutoAssessing: Int,
                       @JsonProperty("total_submissions") val totalSubmissions: Int,
                       @JsonProperty("total_users") val totalUsers: Int)


    @PostMapping("/statistics/common")
    fun controller(@Valid @RequestBody dto: ReqResp?, caller: EasyUser): DeferredResult<ReqResp> {
        log.debug { "${caller.id} is querying statistics." }
        val resp = DeferredResult<ReqResp>()

        when (dto) {
            null -> resp.setResult(statisticsService.createResp())
            statisticsService.resp -> deferredHandling(resp)
            else -> resp.setResult(statisticsService.createResp())
        }

        return resp
    }

    /**
     * Submit request to deferred handling.
     */
    private fun deferredHandling(resp: DeferredResult<ReqResp>) {
        ForkJoinPool.commonPool().submit {
            statisticsService.addRequestToMemory(resp)
            while (!resp.hasResult()) {
                Thread.sleep(100)
            }
        }
    }

}


@Service
class StatisticsService {
    lateinit var resp: StatisticsController.ReqResp
    private var requests = mutableSetOf<DeferredResult<StatisticsController.ReqResp>>()

    init {
        this.resp = createResp()
        notifyAndClearRequests(resp)
    }

    @Synchronized
    fun addRequestToMemory(req: DeferredResult<StatisticsController.ReqResp>) {
        requests.add(req)
    }

    @Synchronized
    fun notifyAndClearRequests(resp: StatisticsController.ReqResp) {
        requests.map { it.setResult(resp) }
        requests.clear()
    }


    @Cacheable("submissions")
    fun selectSubmissionCount() = transaction {
        Submission.selectAll().count()
    }

    @Cacheable("users")
    fun selectTotalUserCount() = transaction {
        Account.selectAll().count()
    }

    @Cacheable("autoassessment")
    fun selectSubmissionsInAutoAssessmentCount() = transaction {
        Submission.select { Submission.autoGradeStatus eq AutoGradeStatus.IN_PROGRESS }.count()
    }

    @Scheduled(fixedDelay = 1000)
    fun queryChangesStatistics() {
        val newResp = createResp()

        if (newResp != resp) {
            notifyAndClearRequests(newResp)
            resp = newResp
            log.debug { "Updated public statistics deferred responses." }
        }

    }

    fun createResp(): StatisticsController.ReqResp {
        return StatisticsController.ReqResp(selectSubmissionsInAutoAssessmentCount(),
                selectSubmissionCount(),
                selectTotalUserCount())
    }

}