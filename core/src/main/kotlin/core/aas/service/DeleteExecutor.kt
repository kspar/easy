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
        // TODO: drain executor should be a separate service
        // TODO: delete executor service should have a body with parameter force: boolean
        // TODO: if force=false and the executor is not drained (i.e. there are waiting or running jobs) then error
        // TODO: if force=true then executor is deleted even if it's not drained (should be logged as a warning)
        futureAutoGradeService.deleteExecutor(executorId.idToLongOrInvalidReq())
    }
}

