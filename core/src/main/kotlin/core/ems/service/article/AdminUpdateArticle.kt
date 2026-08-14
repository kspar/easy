package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.Article
import core.db.ArticleVersion
import core.ems.service.MarkdownService
import core.ems.service.rejectLegacyContentFields
import core.ems.service.assertArticleExists
import core.ems.service.cache.CachingService
import core.ems.service.cache.articleCache
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.singleOrInvalidRequest
import core.exception.InvalidRequestException
import core.exception.ReqError
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class UpdateArticleController(private val markdownService: MarkdownService, private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
        @param:JsonProperty("text_md", required = false) @field:Size(max = 300000) val textMd: String?,
        // Rejected, never read — see rejectLegacyContentFields (EZ-1730).
        @param:JsonProperty("text_adoc", required = false) val legacyTextAdoc: String? = null,
        @param:JsonProperty("published", required = true) val published: Boolean
    )


    @Secured("ROLE_ADMIN")
    @PutMapping("/articles/{articleId}")
    fun controller(@Valid @RequestBody req: Req, @PathVariable("articleId") articleIdString: String, caller: EasyUser) {

        log.info { "${caller.id} is updating article '$articleIdString'" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        assertArticleExists(articleId)

        rejectLegacyContentFields("text_md", "text_adoc" to req.legacyTextAdoc)
        val html = req.textMd?.let { markdownService.mdToHtml(it) }
        updateArticle(caller.id, articleId, req, html)
        cachingService.invalidate(articleCache)
    }

    private fun updateArticle(authorId: String, articleId: Long, req: Req, html: String?) = transaction {
        val time = DateTime.now()

        Article.update({ Article.id eq articleId }) {
            it[published] = req.published
        }

        // singleOrInvalidRequest, not first(): on an empty result first() throws
        // NoSuchElementException, which lands as a 500 and an admin notification e-mail for what is
        // at worst a client error. It also catches the two-open-versions case that first() would
        // silently pick one of.
        val previousVer = ArticleVersion
            .selectAll().where { ArticleVersion.article eq articleId and ArticleVersion.validTo.isNull() }
            .toList()
            .singleOrInvalidRequest()
        val lastVersionId = previousVer[ArticleVersion.id].value

        // text_html is regenerated from text_md on every save, so a request without text_md empties
        // the article — and the old text then survives only in a version history no endpoint
        // exposes. UpdateExercise guards the same way for the same reason.
        if (html == null && !previousVer[ArticleVersion.textHtml].isNullOrEmpty()) {
            throw InvalidRequestException(
                "Updating article $articleId without text_md would blank its text, since text_html " +
                        "is regenerated from text_md.",
                ReqError.INVALID_PARAMETER_VALUE, notify = false
            )
        }

        ArticleVersion.update({ ArticleVersion.id eq lastVersionId }) {
            it[validTo] = time
        }

        ArticleVersion.insert {
            it[title] = req.title
            it[textMd] = req.textMd
            it[textHtml] = html
            it[previous] = EntityID(lastVersionId, ArticleVersion)
            it[validFrom] = time
            it[article] = EntityID(articleId, Article)
            it[author] = EntityID(authorId, Account)
        }

    }
}
