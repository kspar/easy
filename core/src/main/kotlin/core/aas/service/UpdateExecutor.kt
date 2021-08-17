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
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UpdateExecutorController {

    data class Req(
        @JsonProperty("name", required = true) @field:NotBlank @field:Size(max = 100) val name: String,
        @JsonProperty("base_url", required = true) @field:NotBlank @field:Size(max = 2000) val baseUrl: String,
        @JsonProperty("max_load", required = true) val maxLoad: Int,
        @JsonProperty("drain", required = true) val drain: Boolean
    )

    @Secured("ROLE_ADMIN")
    @PutMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String, @Valid @RequestBody body: Req, caller: EasyUser) {

        log.debug { "Update executor $executorId by ${caller.id}" }
        updateExecutor(executorId.idToLongOrInvalidReq(), body)
    }
}

private fun updateExecutor(executorId: Long, body: UpdateExecutorController.Req) {
    return transaction {
        val executorExists =
            Executor.select { Executor.id eq executorId }
                .count() == 1L

        if (executorExists) {
            Executor.update({ Executor.id eq executorId }) {
                it[name] = body.name
                it[baseUrl] = body.baseUrl
                it[maxLoad] = body.maxLoad
                it[drain] = body.drain
            }
        } else {
            throw InvalidRequestException("Executor with id $executorId not found")
        }
    }
}
