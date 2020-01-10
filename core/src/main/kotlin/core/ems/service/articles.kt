package core.ems.service

import core.db.Article
import core.db.ArticleAlias
import core.exception.InvalidRequestException
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
        throw InvalidRequestException("Article alias '$alias' does not exist or is not connected with article '$articleId'")
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

fun aliasToIdOrIdToLong(articleIdString: String): Long {
    return transaction {
        ArticleAlias.slice(ArticleAlias.article)
                .select { ArticleAlias.id eq EntityID(articleIdString, ArticleAlias) }
                .map { it[ArticleAlias.article].value }
                .firstOrNull() ?: articleIdString.idToLongOrInvalidReq()
    }
}