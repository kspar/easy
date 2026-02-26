package core.ems.service

import core.db.Article
import core.db.ArticleAlias
import core.ems.service.article.ReadArticleDetailsController
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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


private fun articleExists(articleId: Long): Boolean = transaction {
    Article.selectAll().where { Article.id eq articleId }.count() == 1L
}

private fun articleAliasExists(articleId: Long, alias: String): Boolean = transaction {
    ArticleAlias
        .selectAll()
        .where { (ArticleAlias.id eq alias) and (ArticleAlias.article eq articleId) }
        .count() == 1L
}

fun selectArticleAliases(articleId: Long): List<ReadArticleDetailsController.RespAlias> = transaction {
    ArticleAlias.select(ArticleAlias.id, ArticleAlias.createdAt, ArticleAlias.owner)
        .where { ArticleAlias.article eq articleId }
        .map {
            ReadArticleDetailsController.RespAlias(
                it[ArticleAlias.id].value,
                it[ArticleAlias.createdAt],
                it[ArticleAlias.owner].value
            )
        }
}
