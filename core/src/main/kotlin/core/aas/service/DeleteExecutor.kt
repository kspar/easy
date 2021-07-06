package core.aas.service

import core.aas.FutureAutoGradeService
import core.ems.service.idToLongOrInvalidReq
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class DeleteExecutorController(private val futureAutoGradeService: FutureAutoGradeService) {

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String) {
        futureAutoGradeService.deleteExecutor(executorId.idToLongOrInvalidReq())
    }
}

