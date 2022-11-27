package core.ems.service.exercise

import com.example.demo.compileTSL
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.joda.time.DateTime
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
        @JsonProperty("scripts") val scripts: List<ScriptResp>?,
        @JsonProperty("feedback") val feedback: String?,
        @JsonProperty("meta") val meta: MetaResp?,
    )

    data class ScriptResp(
        @JsonProperty("name") val name: String,
        @JsonProperty("value") val value: String,
    )

    data class MetaResp(
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("timestamp") val timestamp: DateTime,
        @JsonProperty("compiler_version") val compilerVersion: String,
        @JsonProperty("backend_id") val backendId: String,
        @JsonProperty("backend_version") val backendVersion: String,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/tsl/compile")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.debug { "Compile TSL by ${caller.id}" }

        val result = try {
            compileTSL(dto.tslSpec, "1", "tiivad")
        } catch (e: Exception) {
            return Resp(null, e.message.orEmpty(), null)
        }

        return Resp(
            result.generatedScripts.mapIndexed { i, s ->
                ScriptResp("generated_$i.py", s)
            },
            null,
            MetaResp(DateTime.now(), result.tslCompilerVersion, result.backendID, result.backendVersion)
        )
    }
}