package core.ems.service

import core.db.Article
import core.db.ArticleAlias
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

fun assertArticleExists(articleId: Long) {
    if (!articleExists(articleId)) {
        throw InvalidRequestException("No article with id $articleId found")
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
        Article.select { Article.id eq articleId }.count() == 1
    }
}

private fun articleAliasExists(articleId: Long, alias: String): Boolean {
    return transaction {
        ArticleAlias.select {
            (ArticleAlias.id eq alias) and (ArticleAlias.article eq EntityID(articleId, Article))
        }.count() != 0
    }
}