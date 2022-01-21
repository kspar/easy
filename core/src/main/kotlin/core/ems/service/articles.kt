package core.ems.service

import core.db.Article
import core.db.ArticleAlias
import core.ems.service.article.ReadArticleDetailsController
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

fun assertArticleExists(articleId: Long) {
    if (!articleExists(articleId)) {
        throw InvalidRequestException("No article with id $articleId found", ReqError.ARTICLE_NOT_FOUND)
    }
}


fun assertArticleAliasExists(articleId: Long, alias: String) {
    if (!articleAliasExists(articleId, alias)) {
        throw InvalidRequestException(
            "Article alias '$alias' does not exist or is not connected with article '$articleId'",
            ReqError.ARTICLE_NOT_FOUND
        )
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

fun selectArticleAliases(articleId: Long): List<ReadArticleDetailsController.RespAlias> {
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
