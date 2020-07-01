package core.aas.service

import core.db.Executor
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class DeleteExecutorController {

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String) {
        deleteExecutor(executorId.idToLongOrInvalidReq())
    }
}

private fun deleteExecutor(executorId: Long) {
    return transaction {
        val executorExists =
                Executor.select { Executor.id eq executorId }
                        .count() == 1L

        if (executorExists) {
            Executor.deleteWhere { Executor.id eq executorId }
        } else {
            throw InvalidRequestException("Executor with id $executorId not found")
        }
    }
}
