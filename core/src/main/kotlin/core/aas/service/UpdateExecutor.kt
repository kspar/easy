package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ContainerImage
import core.db.Executor
import core.db.ExecutorContainerImage
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class UpdateExecutorController {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("name", required = true) @field:NotBlank @field:Size(max = 100) val name: String,
        @JsonProperty("base_url", required = true) @field:NotBlank @field:Size(max = 2000) val baseUrl: String,
        @JsonProperty("max_load", required = true) val maxLoad: Int,
        @JsonProperty("drain", required = true) val drain: Boolean,
        @JsonProperty("containers", required = true) val containers: List<String>
    )

    @Secured("ROLE_ADMIN")
    @PutMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String, @Valid @RequestBody body: Req, caller: EasyUser) {

        log.info { "Update executor $executorId by ${caller.id}" }
        updateExecutor(executorId.idToLongOrInvalidReq(), body)
    }

    private fun updateExecutor(executorId: Long, body: Req) = transaction {
        val executorExists = Executor.selectAll().where { Executor.id eq executorId }.count() == 1L

        if (!executorExists) {
            throw InvalidRequestException("Executor with id $executorId not found")
        } else {
            // Check that containers exists and if not, exception.
            body.containers.forEach {

                if (ContainerImage.selectAll().where { ContainerImage.id eq it }.count() != 1L) throw InvalidRequestException(
                    "Container image '$it' not found!",
                    ReqError.ENTITY_WITH_ID_NOT_FOUND,
                    "id" to it
                )
            }

            Executor.update({ Executor.id eq executorId }) {
                it[name] = body.name
                it[baseUrl] = body.baseUrl
                it[maxLoad] = body.maxLoad
                it[drain] = body.drain
            }

            ExecutorContainerImage.deleteWhere { ExecutorContainerImage.executor eq executorId }

            ExecutorContainerImage.batchInsert(body.containers) { item ->
                this[ExecutorContainerImage.containerImage] = item
                this[ExecutorContainerImage.executor] = executorId
            }
        }
    }
}
