package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.Article
import core.db.ArticleVersion
import core.db.StoredFile
import core.ems.service.AdocService
import core.ems.service.assertArticleExists
import core.ems.service.cache.CachingService
import core.ems.service.cache.articleCache
import core.ems.service.idToLongOrInvalidReq
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
class UpdateArticleController(private val adocService: AdocService, private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
        @param:JsonProperty("text_adoc", required = false) @field:Size(max = 300000) val textAdoc: String?,
        @param:JsonProperty("public", required = true) val public: Boolean
    )


    @Secured("ROLE_ADMIN")
    @PutMapping("/articles/{articleId}")
    fun controller(@Valid @RequestBody req: Req, @PathVariable("articleId") articleIdString: String, caller: EasyUser) {

        log.info { "${caller.id} is updating article '$articleIdString'" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        assertArticleExists(articleId)

        val html = req.textAdoc?.let { adocService.adocToHtml(it) }
        updateArticle(caller.id, articleId, req, html)
        cachingService.invalidate(articleCache)
    }

    private fun updateArticle(authorId: String, articleId: Long, req: Req, html: String?) = transaction {
        val time = DateTime.now()

        Article.update({ Article.id eq articleId }) {
            it[public] = req.public
        }

        val lastVersionId = ArticleVersion
            .selectAll().where { ArticleVersion.article eq articleId and ArticleVersion.validTo.isNull() }
            .map { it[ArticleVersion.id].value }
            .first()

        ArticleVersion.update({ ArticleVersion.id eq lastVersionId }) {
            it[validTo] = time
        }

        ArticleVersion.insert {
            it[title] = req.title
            it[textAdoc] = req.textAdoc
            it[textHtml] = html
            it[previous] = EntityID(lastVersionId, ArticleVersion)
            it[validFrom] = time
            it[article] = EntityID(articleId, Article)
            it[author] = EntityID(authorId, Account)
        }

        if (html != null) {
            val inUse = StoredFile.select(StoredFile.id)
                .where { StoredFile.usageConfirmed eq false }
                .map { it[StoredFile.id].value }
                .filter { html.contains(it) }

            StoredFile.update({ StoredFile.id inList inUse }) {
                it[StoredFile.usageConfirmed] = true
                it[StoredFile.article] = EntityID(articleId, Article)
            }
        }
    }
}
