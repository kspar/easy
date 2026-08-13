package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.cache.CachingService
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.annotation.JsonSerialize
import java.io.Serializable


@RestController
@RequestMapping("/v2")
class ReadArticleDetailsController(private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_modified") val lastModified: DateTime,
        @get:JsonProperty("owner") val owner: RespUser,
        @get:JsonProperty("author") val author: RespUser,
        @get:JsonProperty("text_html") val textHtml: String?,
        // The editor's source, for the editor only. A reader gets text_html, and this endpoint is
        // reachable without a login — see the cache-key note in CachingService.
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("text_md") val textMd: String?,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("published") val published: Boolean?,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("aliases") val assets: List<RespAlias>?
    ) : Serializable

    data class RespAlias(
        @get:JsonProperty("id") val id: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("created_by") val createdBy: String
    ) : Serializable

    data class RespUser(
        // A username, so admin-only: this endpoint is readable without a login, and publishing
        // staff usernames to the internet buys nothing. The name is what a byline needs.
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("id") val id: String?,
        @get:JsonProperty("given_name") val givenName: String,
        @get:JsonProperty("family_name") val familyName: String
    ) : Serializable


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/articles/{articleId}")
    fun controller(@PathVariable("articleId") articleIdString: String, caller: EasyUser): Resp {

        log.info { "${caller.id} is reading article '$articleIdString' details" }
        return cachingService.selectLatestArticleVersion(articleIdString, caller.isAdmin())
    }
}