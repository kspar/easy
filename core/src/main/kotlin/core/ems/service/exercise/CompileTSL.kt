package core.ems.service.exercise

import com.example.demo.compileTSL
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.exception.TSLCompileException
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.Size

@RestController
@RequestMapping("/v2")
class CompileTSL {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("tsl_spec") @field:Size(max = 100_000) val tslSpec: String,
    )

    data class Resp(
        @JsonProperty("scripts") val scripts: List<ScriptResp>,
    )

    data class ScriptResp(
        @JsonProperty("name") val name: String,
        @JsonProperty("value") val value: String,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/tsl/compile")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.debug { "Compile TSL by ${caller.id}" }

        val result = try {
            compileTSL(dto.tslSpec, "1", "tiivad")
        } catch (e: Exception) {
            throw TSLCompileException(e.message.orEmpty())
        }

        val resp = Resp(result.generatedScripts.mapIndexed { i, s ->
            ScriptResp("generated-$i.py", s)
        })

        return resp
    }
}