package core.ems.service.article

import core.conf.security.EasyUser
import core.db.ArticleAlias
import core.ems.service.assertArticleAliasExists
import core.ems.service.assertArticleExists
import core.ems.service.cache.CachingService
import core.ems.service.cache.articleCache
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class DeleteArticleAliasController(private val cachingService: CachingService) {

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/articles/{articleId}/aliases/{aliasId}")
    fun controller(
        @PathVariable("articleId") articleIdString: String,
        @PathVariable("aliasId") alias: String,
        caller: EasyUser
    ) {

        log.debug { "${caller.id} is deleting alias '$alias' for the article $articleIdString" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        assertArticleExists(articleId)
        assertArticleAliasExists(articleId, alias)

        deleteAlias(articleId, alias)
        cachingService.invalidate(articleCache)
    }
}


private fun deleteAlias(articleId: Long, alias: String) {
    transaction {
        ArticleAlias.deleteWhere {
            (ArticleAlias.id eq alias) and (ArticleAlias.article eq articleId)
        }
    }
}

