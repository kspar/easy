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
import mu.KotlinLogging
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Pattern
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class CreateArticleAliasController(private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("alias", required = true)
        @field:NotBlank
        @field:Size(max = 100)
        @field:Pattern(regexp = "\\w*[a-zA-Z]\\w*")
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



