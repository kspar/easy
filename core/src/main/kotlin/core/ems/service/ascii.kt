package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import mu.KotlinLogging
import org.asciidoctor.Asciidoctor.Factory.create
import org.asciidoctor.Attributes
import org.asciidoctor.Options
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid


private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AsciiController {


    data class ReqResp(@JsonProperty("content") val content: String)


    @PostMapping("/test/ascii")
    fun controller(@Valid @RequestBody dto: ReqResp, caller: EasyUser): ReqResp {
        log.debug { "${caller.id} is testing asciidoc: ${dto.content}." }

        val attributes = Attributes()
        val options = Options()
        options.setAttributes(attributes)

        val asciidoctor = create()
        return ReqResp(asciidoctor.convert(dto.content, options))
    }
}