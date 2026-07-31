package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import jakarta.validation.Valid
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class AdocPreviewController(private val adocService: AdocService) {
    private val log = KotlinLogging.logger {}

    data class ReqResp(@get:JsonProperty("content") val content: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN", "ROLE_STUDENT")
    @PostMapping("/preview/adoc")
    fun controller(@Valid @RequestBody dto: ReqResp, caller: EasyUser): ReqResp {
        log.info { "Preview adoc by ${caller.id}" }
        return ReqResp(adocService.adocToHtml(dto.content))
    }
}