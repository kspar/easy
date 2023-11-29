package core.ems.service.exercise

import com.example.demo.TSLSpecFormat
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
        @JsonProperty("format") val format: TSLSpecFormat = TSLSpecFormat.JSON,
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
        log.info { "Compile TSL by ${caller.id}" }

        val resp = try {
            compileTSLToResp(dto.tslSpec, dto.format)
        } catch (e: Exception) {  // TODO: do not catch all exceptions, this hides internal compiler errors
            Resp(null, e.message.orEmpty(), null)
        }

        return resp
    }
}

fun compileTSLToResp(spec: String, format: TSLSpecFormat): CompileTSL.Resp {
    val result = compileTSL(spec, "1", "tiivad", format)

    return CompileTSL.Resp(
        result.generatedScripts.mapIndexed { i, s ->
            CompileTSL.ScriptResp("generated_$i.py", s)
        },
        null,
        CompileTSL.MetaResp(DateTime.now(), result.tslCompilerVersion, result.backendID, result.backendVersion)
    )
}