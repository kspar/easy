package ee.urgas.aas.bl.admin

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.db.Executor
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class CreateExecutorController {

    data class CreateExecutorBody(@JsonProperty("name", required = true) val name: String,
                                  @JsonProperty("base_url", required = true) val baseUrl: String,
                                  @JsonProperty("max_load", required = true) val maxLoad: Int)

    data class CreateExecutorResponse(@JsonProperty("id") val id: String)

    @PostMapping("/executors")
    fun createExecutor(@RequestBody body: CreateExecutorBody): CreateExecutorResponse {
        val newExecutor = mapToNewExecutor(body)
        val executorId = insertExecutor(newExecutor)
        return CreateExecutorResponse(executorId.toString())
    }

    private fun mapToNewExecutor(dto: CreateExecutorBody): NewExecutor = NewExecutor(dto.name, dto.baseUrl, dto.maxLoad)
}

private data class NewExecutor(val name: String, val baseUrl: String, val maxLoad: Int)

private fun insertExecutor(newExecutor: NewExecutor): Long {
    return transaction {
        Executor.insertAndGetId {
            it[name] = newExecutor.name
            it[baseUrl] = newExecutor.baseUrl
            it[maxLoad] = newExecutor.maxLoad
            it[load] = 0
        }
    }.value
}
