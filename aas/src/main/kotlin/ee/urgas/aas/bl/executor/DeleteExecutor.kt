package ee.urgas.aas.bl.executor

import ee.urgas.aas.db.Executor
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class DeleteExecutorController {

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/executors/{executorId}")
    fun removeExecutor(@PathVariable("executorId") executorId: String) {
        deleteExecutor(executorId.toLong())
    }
}

private fun deleteExecutor(executorId: Long) {
    return transaction {
        Executor.deleteWhere { Executor.id eq executorId }
    }
}
