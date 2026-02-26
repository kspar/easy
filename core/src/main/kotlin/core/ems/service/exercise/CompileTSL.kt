package core.ems.service.exercise

import com.example.demo.TSLSpecFormat
import com.example.demo.compileTSL
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2")
class CompileTSL {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("tsl_spec") @field:Size(max = 100_000) val tslSpec: String,
        @param:JsonProperty("format") val format: TSLSpecFormat = TSLSpecFormat.JSON,
    )

    data class Resp(
        @get:JsonProperty("scripts") val scripts: List<ScriptResp>?,
        @get:JsonProperty("feedback") val feedback: String?,
        @get:JsonProperty("meta") val meta: MetaResp?,
    )

    data class ScriptResp(
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("value") val value: String,
    )

    data class MetaResp(
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("timestamp") val timestamp: DateTime,
        @get:JsonProperty("compiler_version") val compilerVersion: String,
        @get:JsonProperty("backend_id") val backendId: String,
        @get:JsonProperty("backend_version") val backendVersion: String,
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