package core.ems.service

import core.db.*
import core.ems.service.article.ReadArticleDetailsController
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

fun assertArticleExists(articleId: Long) {
    if (!articleExists(articleId)) {
        throw InvalidRequestException("No article with id $articleId found", ReqError.ARTICLE_NOT_FOUND)
    }
}


fun assertArticleAliasExists(articleId: Long, alias: String) {
    if (!articleAliasExists(articleId, alias)) {
        throw InvalidRequestException("Article alias '$alias' does not exist or is not connected with article '$articleId'",
                ReqError.ARTICLE_NOT_FOUND)
    }
}


private fun articleExists(articleId: Long): Boolean {
    return transaction {
        Article.select { Article.id eq articleId }.count() == 1L
    }
}

private fun articleAliasExists(articleId: Long, alias: String): Boolean {
    return transaction {
        ArticleAlias.select {
            (ArticleAlias.id eq alias) and (ArticleAlias.article eq articleId)
        }.count() == 1L
    }
}

@Service
class ArticleService {

    @Cacheable("articles")
    fun selectLatestArticleVersion(articleIdOrAlias: String, isAdmin: Boolean): ReadArticleDetailsController.Resp {
        return transaction {

            val articleId = ArticleAlias.slice(ArticleAlias.article)
                    .select { ArticleAlias.id eq articleIdOrAlias }
                    .map { it[ArticleAlias.article].value }
                    .singleOrNull() ?: articleIdOrAlias.idToLongOrInvalidReq()

            val authorAlias = Account.alias("author_account_1")
            val adminAlias = Admin.alias("author_admin_1")

            Article.innerJoin(Account innerJoin Admin)
                    .innerJoin(ArticleVersion
                            .innerJoin(adminAlias, { adminAlias[Admin.id] }, { ArticleVersion.author })
                            .innerJoin(authorAlias, { authorAlias[Account.id] }, { adminAlias[Admin.id] }))
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
                        Article.id eq articleId and ArticleVersion.validTo.isNull()
                    }
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
                    }.singleOrNull()
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
}