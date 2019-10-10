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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.Min

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StatisticsController {

    data class Req(@JsonProperty("in_auto_assessing")
                   @field:Min(0) val inAutoAssessing: Int,

                   @JsonProperty("total_submissions")
                   @field:Min(0) val totalSubmissions: Int,

                   @JsonProperty("total_users")
                   @field:Min(0) val totalUsers: Int)


    data class Resp(@JsonProperty("in_auto_assessing") val inAutoAssessing: Int,
                    @JsonProperty("total_submissions") val totalSubmissions: Int,
                    @JsonProperty("total_users") val totalUsers: Int)

    @PostMapping("/statistics/common")
    fun controller(@Valid @RequestBody dto: Req?, caller: EasyUser): Resp {
        log.debug { "${caller.id} is querying statistics." }
        return Resp(-1, -1, -1)
    }
}

fun selectSubmissionCount() = transaction { Submission.selectAll().count() }

fun selectTotalUserCount() = transaction { Account.selectAll().count() }

fun selectSubmissionsInAutoAssessmentCount() {
    transaction {
        Submission.select { Submission.autoGradeStatus eq AutoGradeStatus.IN_PROGRESS }.count()
    }
}