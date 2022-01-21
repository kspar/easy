package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.ems.service.cache.CachingService
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadArticleDetailsController(private val cachingService: CachingService) {

    data class Resp(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("last_modified") val lastModified: DateTime,
        @JsonProperty("owner") val owner: RespUser,
        @JsonProperty("author") val author: RespUser,
        @JsonProperty("text_html") val textHtml: String?,
        @JsonProperty("text_adoc") val textAdoc: String?,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("public") val public: Boolean?,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("aliases") val assets: List<RespAlias>?
    ) : Serializable

    data class RespAlias(
        @JsonProperty("id") val id: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime,
        @JsonProperty("created_by") val createdBy: String
    ) : Serializable

    data class RespUser(
        @JsonProperty("id") val id: String,
        @JsonProperty("given_name") val givenName: String,
        @JsonProperty("family_name") val familyName: String
    ) : Serializable


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/articles/{articleId}")
    fun controller(@PathVariable("articleId") articleIdString: String, caller: EasyUser): Resp {

        log.debug { "Getting article $articleIdString details for ${caller.id}" }
        return cachingService.selectLatestArticleVersion(articleIdString, caller.isAdmin())
    }
}