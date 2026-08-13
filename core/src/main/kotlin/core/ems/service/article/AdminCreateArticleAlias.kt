package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.ArticleAlias
import core.ems.service.assertArticleExists
import core.ems.service.cache.CachingService
import core.ems.service.cache.articleCache
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class CreateArticleAliasController(private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("alias", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        // Letters, digits, underscores and hyphens, with at least one letter.
        //
        // The hyphen is the point of the whole feature: an alias exists so the URL can be written
        // on a slide or read down a phone, and `/a/how-to-log-in` is how a URL is spelled. The
        // original pattern was \w-only, which quietly forced how_to_log_in.
        //
        // At least one letter, still, and that is load-bearing rather than cosmetic: the read path
        // resolves an alias first and falls back to parsing the segment as a numeric id, so an
        // all-digit alias would shadow the article whose id it matches. Requiring a letter makes
        // the two namespaces disjoint, so the lookup order cannot matter. See EZ-1762.
        @field:Pattern(regexp = "[\\w-]*[a-zA-Z][\\w-]*")
        val alias: String
    )

    @Secured("ROLE_ADMIN")
    @PostMapping("/articles/{articleId}/aliases")
    fun controller(@Valid @RequestBody req: Req, @PathVariable("articleId") articleIdString: String, caller: EasyUser) {

        log.info { "${caller.id} is creating alias '${req.alias}' for the article '$articleIdString'" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        assertArticleExists(articleId)

        insertAlias(caller.id, articleId, req.alias)
        cachingService.invalidate(articleCache)
    }

    private fun insertAlias(createdBy: String, articleId: Long, alias: String) {
        transaction {
            if (ArticleAlias.selectAll().where { ArticleAlias.id eq alias }.count() > 0) {
                throw InvalidRequestException(
                    "Article alias '$alias' is already in use.",
                    ReqError.ARTICLE_ALIAS_IN_USE
                )
            }

            ArticleAlias.insert {
                it[id] = alias
                it[owner] = createdBy
                it[article] = articleId
                it[createdAt] = DateTime.now()
            }
        }
    }

}



