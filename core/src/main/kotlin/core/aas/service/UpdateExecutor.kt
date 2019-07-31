package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Executor
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UpdateExecutorController {

    data class Req(@JsonProperty("name", required = true) val name: String,
                   @JsonProperty("base_url", required = true) val baseUrl: String,
                   @JsonProperty("max_load", required = true) val maxLoad: Int)

    @Secured("ROLE_ADMIN")
    @PutMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String, @RequestBody body: Req, caller: EasyUser) {

        log.debug { "Update executor $executorId by ${caller.id}" }
        updateExecutor(executorId.idToLongOrInvalidReq(), body)
    }
}

private fun updateExecutor(executorId: Long, body: UpdateExecutorController.Req) {
    return transaction {
        val executorExists =
                Executor.select { Executor.id eq executorId }
                        .count() == 1

        if (executorExists) {
            Executor.update({ Executor.id eq executorId }) {
                it[name] = body.name
                it[baseUrl] = body.baseUrl
                it[maxLoad] = body.maxLoad
            }
        } else {
            throw InvalidRequestException("Executor with id $executorId not found")
        }
    }
}
