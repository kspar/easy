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


    @PostMapping("/preview/adoc")
    fun controller(@Valid @RequestBody dto: ReqResp, caller: EasyUser): ReqResp {
        log.debug { "${caller.id} is testing asciidoc: ${dto.content}." }

        val asciidoctor = create()

        val options = Options()
        val attributes = Attributes()
        attributes.setSourceHighlighter("highlightjs")
        options.setAttributes(attributes)

        val html = asciidoctor.convert(dto.content, options)

        asciidoctor.shutdown()
        return ReqResp(html)
    }
}