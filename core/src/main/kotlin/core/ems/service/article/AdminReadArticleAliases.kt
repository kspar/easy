package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Article
import core.db.ArticleAlias
import core.db.ArticleVersion
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadArticleAliasesController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("articles") val articles: List<ArticleResp>
    )

    data class ArticleResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("aliases") val aliases: List<RespAlias>?
    )

    data class RespAlias(
        @get:JsonProperty("id") val id: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("created_by") val createdBy: String
    )

    @Secured("ROLE_ADMIN")
    @GetMapping("/article-aliases")
    fun controller(caller: EasyUser): Resp {

        log.info { "${caller.id} is querying all the article aliases" }
        return selectAliases()
    }

    private fun selectAliases(): Resp = transaction {
        Resp(
            (Article innerJoin ArticleVersion)
                .select(Article.id, ArticleVersion.title)
                .where { ArticleVersion.validTo.isNull() }
                .map {
                    ArticleResp(
                        it[Article.id].value.toString(),
                        it[ArticleVersion.title],
                        selectArticleAliases(it[Article.id].value)
                    )
                })
    }

    private fun selectArticleAliases(articleId: Long): List<RespAlias> = transaction {
        ArticleAlias.select(ArticleAlias.id, ArticleAlias.createdAt, ArticleAlias.owner)
            .where { ArticleAlias.article eq articleId }.map {
                RespAlias(
                    it[ArticleAlias.id].value,
                    it[ArticleAlias.createdAt],
                    it[ArticleAlias.owner].value
                )
            }
    }
}
