package core.ems.service.article

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.alias
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
                    @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonProperty("public") val public: Boolean?,
                    @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonProperty("aliases") val assets: List<RespAlias>?)

    data class RespAlias(@JsonProperty("id") val id: String,
                         @JsonSerialize(using = DateTimeSerializer::class)
                         @JsonProperty("created_at") val createdAt: DateTime,
                         @JsonProperty("created_by") val createdBy: String)

    data class RespUser(@JsonProperty("id") val id: String,
                        @JsonProperty("given_name") val givenName: String,
                        @JsonProperty("family_name") val familyName: String)


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/articles/{articleId}")
    fun controller(@PathVariable("articleId") articleIdString: String, caller: EasyUser): Resp {

        log.debug { "Getting article $articleIdString details for ${caller.id}" }
        val articleId = aliasToIdOrIdToLong(articleIdString)

        return selectLatestArticleVersion(articleId, caller.isAdmin())
    }
}


private fun selectLatestArticleVersion(articleId: Long, isAdmin: Boolean): ReadArticleDetailsController.Resp {
    return transaction {
        val authorAlias = Account.alias("account1")
        val adminAlias = Admin.alias("admin1")

        ((Article innerJoin (Account innerJoin Admin)) innerJoin (

                ArticleVersion.join(adminAlias, JoinType.INNER, adminAlias[Admin.id], ArticleVersion.author).join(authorAlias, JoinType.INNER, authorAlias[Account.id], adminAlias[Admin.id])


                ))
                .slice(Article.id,
                        ArticleVersion.title,
                        Article.createdAt,
                        ArticleVersion.validFrom,
                        Article.owner,
                        ArticleVersion.author,
                        ArticleVersion.textHtml,
                        ArticleVersion.textAdoc,
                        Article.public,
                        Account.id,
                        Account.givenName,
                        Account.familyName,
                        authorAlias[Account.id],
                        authorAlias[Account.givenName],
                        authorAlias[Account.familyName])
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

                            ReadArticleDetailsController.RespUser(
                                    it[Account.id].value,
                                    it[Account.givenName],
                                    it[Account.familyName]
                            ),
                            ReadArticleDetailsController.RespUser(
                                    it[authorAlias[Account.id]].value,
                                    it[authorAlias[Account.givenName]],
                                    it[authorAlias[Account.familyName]]
                            ),
                            it[ArticleVersion.textHtml],
                            it[ArticleVersion.textAdoc],
                            if (isAdmin) it[Article.public] else null,
                            if (isAdmin) selectArticleAliases(it[Article.id].value) else null)
                }.firstOrNull()
                ?: throw InvalidRequestException("No article with id $articleId found", ReqError.ARTICLE_NOT_FOUND)
    }
}

private fun selectArticleAliases(articleId: Long): List<ReadArticleDetailsController.RespAlias> {
    return transaction {
        ArticleAlias.slice(ArticleAlias.id, ArticleAlias.createdAt, ArticleAlias.owner)
                .select {
                    ArticleAlias.article eq articleId
                }.map {
                    ReadArticleDetailsController.RespAlias(
                            it[ArticleAlias.id].value,
                            it[ArticleAlias.createdAt],
                            it[ArticleAlias.owner].value
                    )
                }
    }
}

private fun aliasToIdOrIdToLong(articleIdString: String): Long {
    return transaction {
        ArticleAlias.slice(ArticleAlias.article)
                .select { ArticleAlias.id eq EntityID(articleIdString, ArticleAlias) }
                .map { it[ArticleAlias.article].value }
                .firstOrNull() ?: articleIdString.idToLongOrInvalidReq()
    }
}
