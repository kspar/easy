package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import mu.KotlinLogging
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
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.debug { "${caller.id} is querying statistics." }
        return Resp(-1, -1, -1)
    }
}