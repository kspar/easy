package core.ems.service.cache

import core.db.*
import core.ems.service.article.ReadArticleDetailsController
import core.ems.service.article.ReadArticlesController
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectAllAliasesByArticle
import core.ems.service.selectArticleAliases
import core.ems.service.singleOrInvalidRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.io.Serializable

const val accountCache = "account"
const val adminCache = "admin"
const val articleCache = "articles"
const val countSubmissionsInAutoAssessmentCache = "autoassessment"
const val studentCache = "student"
const val countSubmissionsCache = "submissions"
const val teacherCache = "teacher"
const val countTotalUsersCache = "users"


/**
 * Has to be a separate Spring component. Do not use directly - only through other Services.
 */
@Service
class CachingService(val cacheManager: CacheManager) {
    private val log = KotlinLogging.logger {}

    fun invalidate(cacheName: String) {
        log.debug { "Invalidating '$cacheName' cache." }
        cacheManager.getCache(cacheName)?.invalidate()
    }

    fun invalidateAll() = cacheManager.cacheNames.forEach { invalidate(it) }

    fun evictAccountCache(username: String) {
        log.debug { "Evicting 'account' cache for $username." }
        cacheManager.getCache(accountCache)?.evict(username)
    }

    /**
     * Every article, newest-looking first by title, for the admin index.
     *
     * Deliberately in the same cache region as [selectLatestArticleVersion] — every article write
     * path already calls `invalidate(articleCache)`, so this is correctly invalidated by code that
     * already exists. A region of its own would be one more place to remember, and the one that
     * gets forgotten serves a deleted article's title forever.
     */
    @Cacheable(articleCache)
    fun selectArticles(): ReadArticlesController.Resp = transaction {
        val aliases = selectAllAliasesByArticle()

        ReadArticlesController.Resp(
            Article.innerJoin(ArticleVersion)
                .select(
                    Article.id,
                    Article.createdAt,
                    Article.published,
                    ArticleVersion.title,
                    ArticleVersion.validFrom,
                )
                .where { ArticleVersion.validTo.isNull() }
                // Titles are not unique, so the id tiebreaker is what makes the order stable
                // rather than merely sorted.
                .orderBy(ArticleVersion.title to SortOrder.ASC, Article.id to SortOrder.ASC)
                .map {
                    val id = it[Article.id].value
                    ReadArticlesController.ArticleResp(
                        id.toString(),
                        it[ArticleVersion.title],
                        aliases[id].orEmpty(),
                        it[Article.createdAt],
                        it[ArticleVersion.validFrom],
                        it[Article.published],
                    )
                }
        )
    }

    /**
     * The current version of an article, by id or alias.
     *
     * **`isAdmin` is the only input that may change this payload.** Anonymous and signed-in
     * non-admin callers share one cache entry — they are both `isAdmin = false` — so they must get
     * byte-identical responses. If a third viewer class ever needs something different, widen the
     * key *first*: otherwise whichever caller populates the entry decides what the other one sees,
     * request order becomes the access control, and nothing fails loudly enough to notice.
     */
    @Cacheable(articleCache)
    fun selectLatestArticleVersion(articleIdOrAlias: String, isAdmin: Boolean): ReadArticleDetailsController.Resp =
        transaction {

            // Deliberately not filtered on published: an unpublished article's alias still resolves
            // to an id, and the main query below then finds nothing. Filtering here as well would
            // be a second place for the visibility rule to live.
            val articleId = ArticleAlias.select(ArticleAlias.article)
                .where { ArticleAlias.id eq articleIdOrAlias }
                .map { it[ArticleAlias.article].value }
                .singleOrNull() ?: articleIdOrAlias.idToLongOrInvalidReq()

            val authorAlias = Account.alias("author_account_1")
            val ownerAlias = Account.alias("author_owner_1")

            Article.innerJoin(Account)
                .innerJoin(
                    ArticleVersion
                        .innerJoin(ownerAlias, { ownerAlias[Account.id] }, { ArticleVersion.author })
                        .innerJoin(authorAlias, { authorAlias[Account.id] }, { ownerAlias[Account.id] })
                )
                .select(
                    Article.id,
                    ArticleVersion.title,
                    Article.createdAt,
                    ArticleVersion.validFrom,
                    Article.owner,
                    ArticleVersion.author,
                    ArticleVersion.textHtml,
                    ArticleVersion.textMd,
                    Article.published,
                    Account.id,
                    Account.givenName,
                    Account.familyName,
                    authorAlias[Account.id],
                    authorAlias[Account.givenName],
                    authorAlias[Account.familyName]
                )
                // A draft is filtered out rather than found and refused, so it produces the exact
                // ENTITY_WITH_ID_NOT_FOUND that a nonexistent id produces — one code path, so the
                // two answers cannot drift apart. That matters here more than usual: aliases are
                // short guessable words on an endpoint reachable with no account, so a
                // distinguishable "forbidden" would turn the alias namespace into a dictionary
                // attack, and for a draft the title is usually the whole secret.
                .where {
                    val current = Article.id eq articleId and ArticleVersion.validTo.isNull()
                    if (isAdmin) current else current and (Article.published eq true)
                }
                .map {
                    ReadArticleDetailsController.Resp(
                        it[Article.id].value.toString(),
                        it[ArticleVersion.title],
                        it[Article.createdAt],
                        it[ArticleVersion.validFrom],

                        ReadArticleDetailsController.RespUser(
                            if (isAdmin) it[Account.id].value else null,
                            it[Account.givenName],
                            it[Account.familyName]
                        ),
                        ReadArticleDetailsController.RespUser(
                            if (isAdmin) it[authorAlias[Account.id]].value else null,
                            it[authorAlias[Account.givenName]],
                            it[authorAlias[Account.familyName]]
                        ),
                        it[ArticleVersion.textHtml],
                        if (isAdmin) it[ArticleVersion.textMd] else null,
                        if (isAdmin) it[Article.published] else null,
                        if (isAdmin) selectArticleAliases(it[Article.id].value) else null
                    )
                }.singleOrInvalidRequest()
        }


    data class Acc(
        val id: String,
        val email: String,
        val givenName: String,
        val familyName: String,
        val createdAt: DateTime,
        val isIdMigrated: Boolean,
        val preMigrationId: String?,
    ) : Serializable

    @Cacheable(value = [accountCache], unless = "#result == null")
    fun selectAccount(username: String): Acc? = transaction {
        log.debug { "$username not in 'account' cache. Executing select." }
        Account.selectAll().where { Account.id eq username }
            .map {
                Acc(
                    it[Account.id].value,
                    it[Account.email],
                    it[Account.givenName],
                    it[Account.familyName],
                    it[Account.createdAt],
                    it[Account.idMigrationDone],
                    it[Account.preMigrationId],
                )
            }.singleOrNull()
    }

    @Cacheable(value = [studentCache], unless = "#result == false")
    fun studentExists(studentUsername: String): Boolean = transaction {
        log.debug { "$studentUsername not in 'student' cache. Executing select." }
        Account.selectAll().where { Account.id eq studentUsername and Account.isStudent }.count() == 1L
    }

    @Cacheable(value = [teacherCache], unless = "#result == false")
    fun teacherExists(teacherUsername: String): Boolean = transaction {
        log.debug { "$teacherUsername not in 'teacher' cache. Executing select." }
        Account.selectAll().where { Account.id eq teacherUsername and Account.isTeacher }.count() == 1L
    }

    @Cacheable(value = [adminCache], unless = "#result == false")
    fun adminExists(adminUsername: String): Boolean = transaction {
        log.debug { "$adminUsername not in 'admin' cache. Executing select." }
        Account.selectAll().where { Account.id eq adminUsername and Account.isAdmin }.count() == 1L
    }

    @Cacheable(countSubmissionsCache)
    fun countSubmissions() = transaction { Submission.selectAll().count() }

    @Cacheable(countTotalUsersCache)
    fun countTotalUsers() = transaction { Account.selectAll().count() }

    @Cacheable(countSubmissionsInAutoAssessmentCache)
    fun countSubmissionsInAutoAssessment() = transaction {
        Submission.selectAll().where { Submission.autoGradeStatus eq AutoGradeStatus.IN_PROGRESS }.count()
    }
}

