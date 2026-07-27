package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.conf.security.EasyUser
import core.db.Executor
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class CreateExecutorController(private val autoGradeScheduler: AutoGradeScheduler) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("name", required = true) @field:NotBlank @field:Size(max = 100) val name: String,
        @param:JsonProperty("base_url", required = true) @field:NotBlank @field:Size(max = 2000) val baseUrl: String,
        @param:JsonProperty("max_load", required = true) val maxLoad: Int
    )

    data class Resp(@get:JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/executors")
    fun controller(@Valid @RequestBody body: Req, caller: EasyUser): Resp {
        log.info { "${caller.id} is creating executor (name = ${body.name})" }

        val executorId = insertExecutor(body)
        autoGradeScheduler.addExecutorsFromDB()
        return Resp(executorId.toString())
    }

    private fun insertExecutor(newExecutor: Req): Long = transaction {
        Executor.insertAndGetId {
            it[name] = newExecutor.name
            it[baseUrl] = newExecutor.baseUrl
            it[maxLoad] = newExecutor.maxLoad
            it[drain] = false
        }
    }.value
}

