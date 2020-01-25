package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Article
import core.db.ArticleAlias
import core.db.ArticleVersion
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadArticleAliasesController {

    data class Resp(@JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonProperty("articles") val articles: List<ArticleResp>)

    data class ArticleResp(@JsonProperty("id") val id: String,
                           @JsonProperty("title") val title: String,
                           @JsonInclude(JsonInclude.Include.NON_NULL)
                           @JsonProperty("aliases") val aliases: List<RespAlias>?)

    data class RespAlias(@JsonProperty("id") val id: String,
                         @JsonSerialize(using = DateTimeSerializer::class)
                         @JsonProperty("created_at") val createdAt: DateTime,
                         @JsonProperty("created_by") val createdBy: String)

    @Secured("ROLE_ADMIN")
    @GetMapping("/article-aliases")
    fun controller(caller: EasyUser): Resp {

        log.debug { "${caller.id} is querying all the article aliases" }
        return selectAliases()
    }
}

private fun selectAliases(): ReadArticleAliasesController.Resp {
    return transaction {
        ReadArticleAliasesController.Resp(
                (Article innerJoin ArticleVersion)
                        .slice(Article.id, ArticleVersion.title)
                        .select { ArticleVersion.validTo.isNull() }
                        .map {
                            ReadArticleAliasesController.ArticleResp(
                                    it[Article.id].value.toString(),
                                    it[ArticleVersion.title],
                                    selectArticleAliases(it[Article.id].value)
                            )
                        })
    }
}

private fun selectArticleAliases(articleId: Long): List<ReadArticleAliasesController.RespAlias> {
    return transaction {
        ArticleAlias.slice(ArticleAlias.id, ArticleAlias.createdAt, ArticleAlias.owner)
                .select {
                    ArticleAlias.article eq articleId
                }.map {
                    ReadArticleAliasesController.RespAlias(
                            it[ArticleAlias.id].value,
                            it[ArticleAlias.createdAt],
                            it[ArticleAlias.owner].value
                    )
                }
    }
}

