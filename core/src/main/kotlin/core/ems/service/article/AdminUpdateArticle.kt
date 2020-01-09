package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.Article
import core.db.ArticleVersion
import core.ems.service.AdocService
import core.ems.service.assertArticleExists
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UpdateArticleController(private val adocService: AdocService) {

    data class Req(@JsonProperty("title", required = true) @field:NotBlank @field:Size(max = 100) val title: String,
                   @JsonProperty("text_adoc", required = false) @field:Size(max = 300000) val textAdoc: String?,
                   @JsonProperty("public", required = true) val public: Boolean)


    @Secured("ROLE_ADMIN")
    @PutMapping("/articles/{articleId}")
    fun controller(@Valid @RequestBody req: Req, @PathVariable("articleId") articleIdString: String, caller: EasyUser) {

        //TODO: articleIdString can be also a alias

        log.debug { "Update article '$articleIdString' by ${caller.id}" }

        val articleId = articleIdString.idToLongOrInvalidReq()

        assertArticleExists(articleId)

        when (req.textAdoc) {
            null -> updateArticle(caller.id, articleId, req, null).toString()
            else -> updateArticle(caller.id, articleId, req, adocService.adocToHtml(req.textAdoc)).toString()
        }
    }
}

private fun updateArticle(authorId: String, articleId: Long, req: UpdateArticleController.Req, html: String?) {
    return transaction {

        Article.update({ Article.id eq articleId }) {
            it[public] = req.public
        }

        val currentArticleVersion = (ArticleVersion innerJoin Article)
                .slice(ArticleVersion.title,
                        ArticleVersion.id,
                        ArticleVersion.textAdoc,
                        ArticleVersion.textHtml,
                        ArticleVersion.validFrom,
                        ArticleVersion.validTo)
                .select {
                    Article.id eq articleId
                }
                .orderBy(ArticleVersion.id, SortOrder.DESC)
                .first()

        ArticleVersion.insert {
            it[title] = req.title
            it[textAdoc] = req.textAdoc
            it[textHtml] = html
            it[previous] = currentArticleVersion[ArticleVersion.id]
            it[validFrom] = currentArticleVersion[validFrom]
            it[validTo] = currentArticleVersion[validTo]
            it[article] = EntityID(articleId, Article)
            it[author] = EntityID(authorId, Account)
        }
    }
}

