package core.ems.service.article

import core.conf.security.EasyUser
import core.db.Article
import core.db.ArticleAlias
import core.db.ArticleVersion
import core.ems.service.assertArticleExists
import core.ems.service.assertArticleIsNotPublished
import core.ems.service.cache.CachingService
import core.ems.service.cache.articleCache
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/**
 * Deleting an article, its whole version history, its aliases and its images.
 *
 * By id, not alias: deleting by a name that can be reassigned is a foot-gun.
 *
 * **Refused while the article is published**, which is a confirmation step rather than a security
 * control — the same admin can unpublish and delete a second later. It is worth the extra step
 * because this is the one operation that destroys the version chain the schema exists to keep,
 * there is no soft delete and no audit trail, and a published article has a public URL that
 * course materials and e-mails may already point at. The codebase asks for the same kind of
 * detach-first elsewhere: assertExerciseIsNotOnAnyCourse, DIR_NOT_EMPTY, GROUP_NOT_EMPTY.
 */
@RestController
@RequestMapping("/v2")
class DeleteArticleController(private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_ADMIN")
    @DeleteMapping("/articles/{articleId}")
    fun controller(@PathVariable("articleId") articleIdString: String, caller: EasyUser) {

        log.info { "${caller.id} is deleting article '$articleIdString'" }
        val articleId = articleIdString.idToLongOrInvalidReq()

        assertArticleExists(articleId)
        assertArticleIsNotPublished(articleId)

        deleteArticle(articleId)
        cachingService.invalidate(articleCache)
    }

    private fun deleteArticle(articleId: Long) = transaction {
        ArticleAlias.deleteWhere { ArticleAlias.article eq articleId }

        // Nothing here touches the article's images. stored_file no longer records what a file
        // belongs to, and StoredFileSweep is the only thing that deletes an object — so an image
        // that was only in this article becomes unreferenced now and is reaped on the next nightly
        // run. That also fixes the flaw this code used to carry: article_id was set once, when a
        // file was first referenced, so an image pasted into a second article still belonged to the
        // first and was deleted along with it.

        // Break the self-reference before the rows go. Postgres would in fact accept deleting the
        // whole chain in one statement — NO ACTION is checked at end-of-statement, which is what
        // DeleteExercise relies on — but that is a subtlety a reader has to know to follow the
        // code, and it stops being true the day the constraint becomes RESTRICT.
        ArticleVersion.update({ ArticleVersion.article eq articleId }) {
            it[previous] = null
        }
        ArticleVersion.deleteWhere { ArticleVersion.article eq articleId }

        Article.deleteWhere { Article.id eq articleId }
    }
}
