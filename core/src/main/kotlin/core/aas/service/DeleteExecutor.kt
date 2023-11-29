package core.aas.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.AutoGradeScheduler
import core.conf.security.EasyUser
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid

@RestController
@RequestMapping("/v2")
class DeleteExecutorController(private val autoGradeScheduler: AutoGradeScheduler) {
    private val log = KotlinLogging.logger {}

    data class Req(@JsonProperty("force") val force: Boolean)

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/executors/{executorId}")
    fun controller(@PathVariable("executorId") executorId: String, @Valid @RequestBody req: Req, caller: EasyUser) {
        log.info { "Deleting executor $executorId by ${caller.id}" }
        autoGradeScheduler.deleteExecutor(executorId.idToLongOrInvalidReq(), req.force)
    }
}
