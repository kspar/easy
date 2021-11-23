package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.db.Executor
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

@RestController
@RequestMapping("/v2")
class CreateExecutorController(private val autoGradeScheduler: AutoGradeScheduler) {

    data class Req(
        @JsonProperty("name", required = true) @field:NotBlank @field:Size(max = 100) val name: String,
        @JsonProperty("base_url", required = true) @field:NotBlank @field:Size(max = 2000) val baseUrl: String,
        @JsonProperty("max_load", required = true) val maxLoad: Int
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/executors")
    fun controller(@Valid @RequestBody body: Req): Resp {
        val executorId = insertExecutor(body)
        autoGradeScheduler.addExecutorsFromDB()
        return Resp(executorId.toString())
    }
}

private fun insertExecutor(newExecutor: CreateExecutorController.Req): Long {
    return transaction {
        Executor.insertAndGetId {
            it[name] = newExecutor.name
            it[baseUrl] = newExecutor.baseUrl
            it[maxLoad] = newExecutor.maxLoad
            it[drain] = false
        }
    }.value
}
