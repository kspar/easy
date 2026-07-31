package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Executor
import core.db.ExecutorContainerImage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadExecutorController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("base_url") val baseUrl: String,
        @get:JsonProperty("max_load") val maxLoad: Int,
        @get:JsonProperty("drain") val drain: Boolean,
        @get:JsonProperty("containers") val containers: List<String>
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
