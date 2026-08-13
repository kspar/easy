package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.ems.service.cache.CachingService
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable


/**
 * Every article, for the admin index.
 *
 * Admin-only, and that is the product decision rather than a limitation: a reader arrives at an
 * article by a link from a course page, an e-mail or the IdP, so nobody but an admin needs to
 * enumerate them. It also means there is no published filter here and no shape that varies by
 * role — the two things that make a listing endpoint fiddly.
 *
 * **No paging.** Articles number in the tens and a row is a title and some aliases. Adding
 * `?offset=&limit=` later is purely additive, which is why the response is a named object rather
 * than a bare array. (Do not copy the paging in ReadLatestTeacherSubmissions, which returns the
 * page size as `count` and so cannot tell a client how many pages there are.)
 */
@RestController
@RequestMapping("/v2")
class ReadArticlesController(private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("articles") val articles: List<ArticleResp>
    ) : Serializable

    data class ArticleResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("title") val title: String,
        // Plain strings: the link target is /a/<alias>, so the string is the whole of what a
        // consumer needs. Per-alias created_at/created_by stays on the detail endpoint.
        @get:JsonProperty("aliases") val aliases: List<String>,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_modified") val lastModified: DateTime,
        @get:JsonProperty("published") val published: Boolean
    ) : Serializable

    @Secured("ROLE_ADMIN")
    @GetMapping("/articles")
    fun controller(caller: EasyUser): Resp {
        log.info { "${caller.id} is listing articles" }
        return cachingService.selectArticles()
    }
}
