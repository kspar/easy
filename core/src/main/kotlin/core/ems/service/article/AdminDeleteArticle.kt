package core.ems.service.article

import core.conf.security.EasyUser
import core.db.Article
import core.db.ArticleAlias
import core.db.ArticleVersion
import core.db.StoredFile
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

        // The images go with the article, and this is the only chance to reclaim them: nothing in
        // core/ems/cron reaps stored files, and usage_confirmed is read by nothing, so merely
        // nulling article_id would strand a bytea blob that nothing can reach and nothing will
        // ever delete. DeleteExercise does the same. Logged so a backup can put them back.
        //
        // Known edge: stored_file.article_id is set once, when a file is first referenced, so an
        // image pasted into a second article still belongs to the first and would go with it.
        // Theoretical while the corpus is this small; a real flaw in the single-valued model.
        val fileIds = StoredFile.select(StoredFile.id)
            .where { StoredFile.article eq articleId }
            .map { it[StoredFile.id].value }
        if (fileIds.isNotEmpty()) {
            log.info { "Deleting ${fileIds.size} stored file(s) with article $articleId: $fileIds" }
            StoredFile.deleteWhere { StoredFile.article eq articleId }
        }

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
