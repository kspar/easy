package ee.urgas.aas.bl.executor

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.aas.db.Executor
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class ReadExecutorController {

    data class ReadExecutorResponse(@JsonProperty("id") val id: String,
                                    @JsonProperty("name") val name: String,
                                    @JsonProperty("base_url") val baseUrl: String,
                                    @JsonProperty("load") val load: Int,
                                    @JsonProperty("max_load") val maxLoad: Int)

    @Secured("ROLE_TEACHER")
    @GetMapping("/executors")
    fun readExecutor(): List<ReadExecutorResponse> {
        val executors = selectAllExecutors()
        return mapToReadExecutorResponses(executors)
    }

    private fun mapToReadExecutorResponses(executors: List<ReadExecutor>): List<ReadExecutorResponse> =
            executors.map { ex -> ReadExecutorResponse(ex.id.toString(), ex.name, ex.baseUrl, ex.load, ex.maxLoad) }
}

private data class ReadExecutor(val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int)

private fun selectAllExecutors(): List<ReadExecutor> {
    return transaction {
        Executor.selectAll().sortedBy { Executor.id }
                .map {
                    ReadExecutor(
                            it[Executor.id].value,
                            it[Executor.name],
                            it[Executor.baseUrl],
                            it[Executor.load],
                            it[Executor.maxLoad]
                    )
                }
    }
}
