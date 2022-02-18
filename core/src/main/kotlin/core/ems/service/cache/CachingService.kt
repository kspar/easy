package core.ems.service.cache

import core.db.*
import core.ems.service.Grade
import core.ems.service.article.ReadArticleDetailsController
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectArticleAliases
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import java.io.Serializable

const val accountCache = "account"
const val adminCache = "admin"
const val articleCache = "articles"
const val countSubmissionsInAutoAssessmentCache = "autoassessment"
const val selectLatestValidGradesCache = "selectLatestValidGrades"
const val studentCache = "student"
const val countSubmissionsCache = "submissions"
const val teacherCache = "teacher"
const val countTotalUsersCache = "users"

private val log = KotlinLogging.logger {}


/**
 * Has to be a separate Spring component. Do not use directly - only through other Services.
 */
@Service
class CachingService(val cacheManager: CacheManager) {

    fun invalidate(cacheName: String) {
        log.debug { "Invalidating '$cacheName' cache." }
        cacheManager.getCache(cacheName)?.invalidate()
    }

    fun invalidateAll() = cacheManager.cacheNames.forEach { invalidate(it) }

    fun evictAccountCache(username: String) {
        log.debug { "Evicting 'account' cache for $username." }
        cacheManager.getCache(accountCache)?.evict(username)
    }

    fun evictSelectLatestValidGrades(courseExerciseId: Long) {
        log.debug { "Evicting 'selectLatestValidGrades' cache for $courseExerciseId." }
        cacheManager.getCache(selectLatestValidGradesCache)?.evict(courseExerciseId)
    }


    @Cacheable(articleCache)
    fun selectLatestArticleVersion(articleIdOrAlias: String, isAdmin: Boolean): ReadArticleDetailsController.Resp {
        return transaction {

            val articleId = ArticleAlias.slice(ArticleAlias.article)
                .select { ArticleAlias.id eq articleIdOrAlias }
                .map { it[ArticleAlias.article].value }
                .singleOrNull() ?: articleIdOrAlias.idToLongOrInvalidReq()

            val authorAlias = Account.alias("author_account_1")
            val adminAlias = Admin.alias("author_admin_1")

            Article.innerJoin(Account innerJoin Admin)
                .innerJoin(
                    ArticleVersion
                        .innerJoin(adminAlias, { adminAlias[Admin.id] }, { ArticleVersion.author })
                        .innerJoin(authorAlias, { authorAlias[Account.id] }, { adminAlias[Admin.id] })
                )
                .slice(
                    Article.id,
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
                    authorAlias[Account.familyName]
                )
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
                        if (isAdmin) selectArticleAliases(it[Article.id].value) else null
                    )
                }.singleOrNull()
                ?: throw InvalidRequestException("No article with id $articleId found", ReqError.ARTICLE_NOT_FOUND)
        }
    }


    @Cacheable(selectLatestValidGradesCache)
    @Suppress("SENSELESS_COMPARISON")
    fun selectLatestValidGradesAll(courseExerciseId: Long): List<Grade> {
        log.debug { "$courseExerciseId not in 'selectLatestValidGrades' cache. Executing select." }
        return transaction {
            (Submission leftJoin TeacherAssessment leftJoin AutomaticAssessment)
                .slice(
                    Submission.id,
                    Submission.student,
                    TeacherAssessment.id,
                    TeacherAssessment.grade,
                    TeacherAssessment.feedback,
                    AutomaticAssessment.id,
                    AutomaticAssessment.grade,
                    AutomaticAssessment.feedback
                )
                .select { Submission.courseExercise eq courseExerciseId }
                .orderBy(Submission.createdAt, SortOrder.DESC)
                .distinctBy { it[Submission.student] }
                .map {
                    when {
                        it[TeacherAssessment.id] != null -> Grade(
                            it[Submission.id].value.toString(),
                            it[Submission.student].value,
                            it[TeacherAssessment.grade],
                            GraderType.TEACHER,
                            it[TeacherAssessment.feedback]
                        )
                        it[AutomaticAssessment.id] != null -> Grade(
                            it[Submission.id].value.toString(),
                            it[Submission.student].value,
                            it[AutomaticAssessment.grade],
                            GraderType.AUTO,
                            it[AutomaticAssessment.feedback]
                        )
                        else -> Grade(
                            it[Submission.id].value.toString(),
                            it[Submission.student].value,
                            null,
                            null,
                            null
                        )
                    }
                }
        }
    }

    data class Acc(
        val id: String,
        val email: String,
        val moodleUsername: String?,
        val givenName: String,
        val familyName: String,
        val createdAt: DateTime,
        val isIdMigrated: Boolean,
        val preMigrationId: String?,
    ) : Serializable

    @Cacheable(value = [accountCache], unless = "#result == null")
    fun selectAccount(username: String): Acc? {
        log.debug { "$username not in 'account' cache. Executing select." }
        return transaction {
            Account.select { Account.id eq username }
                .map {
                    Acc(
                        it[Account.id].value,
                        it[Account.email],
                        it[Account.moodleUsername],
                        it[Account.givenName],
                        it[Account.familyName],
                        it[Account.createdAt],
                        it[Account.idMigrationDone],
                        it[Account.preMigrationId],
                    )
                }.singleOrNull()
        }
    }

    @Cacheable(value = [studentCache], unless = "#result == false")
    fun studentExists(studentUsername: String): Boolean {
        log.debug { "$studentUsername not in 'student' cache. Executing select." }
        return transaction {
            Student.select { Student.id eq studentUsername }.count() == 1L
        }
    }

    @Cacheable(value = [teacherCache], unless = "#result == false")
    fun teacherExists(teacherUsername: String): Boolean {
        log.debug { "$teacherUsername not in 'teacher' cache. Executing select." }
        return transaction {
            Teacher.select { Teacher.id eq teacherUsername }.count() == 1L
        }
    }

    @Cacheable(value = [adminCache], unless = "#result == false")
    fun adminExists(adminUsername: String): Boolean {
        log.debug { "$adminUsername not in 'admin' cache. Executing select." }
        return transaction {
            Admin.select { Admin.id eq adminUsername }.count() == 1L
        }
    }

    @Cacheable(countSubmissionsCache)
    fun countSubmissions() = transaction { Submission.selectAll().count() }

    @Cacheable(countTotalUsersCache)
    fun countTotalUsers() = transaction { Account.selectAll().count() }

    @Cacheable(countSubmissionsInAutoAssessmentCache)
    fun countSubmissionsInAutoAssessment() = transaction {
        Submission.select { Submission.autoGradeStatus eq AutoGradeStatus.IN_PROGRESS }.count()
    }
}

