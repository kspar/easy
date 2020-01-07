package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Account
import core.db.Article
import core.db.ArticleAlias
import core.db.ArticleVersion
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadArticleDetailsController {

    data class Resp(@JsonProperty("id") val id: String,
                    @JsonProperty("title") val title: String,
                    @JsonSerialize(using = DateTimeSerializer::class)
                    @JsonProperty("created_at") val createdAt: DateTime,
                    @JsonSerialize(using = DateTimeSerializer::class)
                    @JsonProperty("last_modified") val lastModified: DateTime,
                    @JsonProperty("owner") val owner: RespUser,
                    @JsonProperty("author") val author: RespUser,
                    @JsonProperty("text_html") val textHtml: String?,
                    @JsonProperty("text_adoc") val textAdoc: String?,
                    @JsonProperty("public") val public: Boolean?, //TODO: only for admins
                    @JsonProperty("aliases") val assets: List<RespAlias>?) //TODO: only for admins

    data class RespAlias(@JsonProperty("id") val id: String,
                         @JsonSerialize(using = DateTimeSerializer::class)
                         @JsonProperty("created_at") val createdAt: DateTime,
                         @JsonProperty("created_by") val createdBy: String)

    data class RespUser(@JsonProperty("id") val id: String,
                        @JsonProperty("given_name") val givenName: String,
                        @JsonProperty("family_name") val familyName: String)


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/articles/{articleId}")
    fun controller(@PathVariable("articleId") articleIdString: String, caller: EasyUser): Resp? {

        log.debug { "Getting article details for ${caller.id}" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        //TODO: if is present?
        return selectLatestArticleVersion(articleId, caller.isAdmin())
    }
}


private fun selectAccount(accountId: String): ReadArticleDetailsController.RespUser {
    return transaction {
        Account.slice(Account.id, Account.givenName, Account.familyName)
                .select {
                    Account.id eq accountId
                }.map {
                    ReadArticleDetailsController.RespUser(
                            it[Account.id].value,
                            it[Account.givenName],
                            it[Account.familyName]
                    )
                }.first()
    }
}


private fun selectArticleAliases(articleId: Long): List<ReadArticleDetailsController.RespAlias> {
    return transaction {
        ArticleAlias.slice(ArticleAlias.alias, ArticleAlias.createdAt, ArticleAlias.owner)
                .select {
                    ArticleAlias.article eq articleId
                }.map {
                    ReadArticleDetailsController.RespAlias(
                            it[ArticleAlias.alias].value,
                            it[ArticleAlias.createdAt],
                            it[ArticleAlias.owner].value
                    )
                }
    }
}

private fun selectLatestArticleVersion(articleId: Long, isAdmin: Boolean): ReadArticleDetailsController.Resp? {
    return transaction {
        //TODO: add slice
        (Article innerJoin ArticleVersion)
                .select {
                    Article.id eq articleId
                }
                .orderBy(ArticleVersion.validFrom, SortOrder.DESC)
                .map {
                    ReadArticleDetailsController.Resp(
                            it[Article.id].value.toString(),
                            it[ArticleVersion.title],
                            it[Article.createdAt],
                            it[ArticleVersion.validFrom],
                            selectAccount(it[Article.owner].value),
                            selectAccount(it[ArticleVersion.author].value),
                            it[ArticleVersion.textHtml],
                            it[ArticleVersion.textAdoc],
                            it[Article.public], // TODO: only if admin
                            selectArticleAliases(it[Article.id].value)) //TODO: only if admins
                }.firstOrNull()
    }
}
