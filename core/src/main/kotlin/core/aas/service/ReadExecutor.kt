package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Executor
import core.db.ExecutorContainerImage
import mu.KotlinLogging
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadExecutorController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("base_url") val baseUrl: String,
        @JsonProperty("max_load") val maxLoad: Int,
        @JsonProperty("drain") val drain: Boolean,
        @JsonProperty("containers") val containers: List<String>
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/executors")
    fun controller(caller: EasyUser): List<Resp> {
        log.info { "Getting executors for ${caller.id}" }
        return selectAllExecutors()
    }

    private fun selectAllExecutors(): List<Resp> = transaction {
        Executor.selectAll().sortedBy { Executor.id }
            .map {
                Resp(
                    it[Executor.id].value.toString(),
                    it[Executor.name],
                    it[Executor.baseUrl],
                    it[Executor.maxLoad],
                    it[Executor.drain],

                    ExecutorContainerImage
                        .selectAll().where { ExecutorContainerImage.executor eq it[Executor.id] }
                        .map { image -> image[ExecutorContainerImage.containerImage].value }
                )
            }
    }
}
