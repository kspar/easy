package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.FutureAutoGradeService
import core.ems.service.idToLongOrInvalidReq
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

@RestController
@RequestMapping("/v2")
class DeleteExecutorController(private val futureAutoGradeService: FutureAutoGradeService) {

    data class Req(@JsonProperty("force") val force: Boolean)

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String, @Valid @RequestBody req: Req) {
        futureAutoGradeService.deleteExecutor(executorId.idToLongOrInvalidReq(), req.force)
    }
}

