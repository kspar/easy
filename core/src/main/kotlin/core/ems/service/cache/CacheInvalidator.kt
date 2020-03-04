package core.ems.service.cache

import mu.KotlinLogging
import org.springframework.cache.CacheManager
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}


@Component
class CacheInvalidator(val cacheManager: CacheManager) {

    @Async
    fun invalidateSubmissionCache() {
        log.debug { "Invalidating 'submissions' cache." }
        cacheManager.getCache("submissions")?.invalidate()
    }

    @Async
    fun invalidateAutoAssessmentCountCache() {
        log.debug { "Invalidating 'autoassessment' cache." }
        cacheManager.getCache("autoassessment")?.invalidate()
    }

    @Async
    fun invalidateTotalUserCache() {
        log.debug { "Invalidating total number of 'users' cache." }
        cacheManager.getCache("users")?.invalidate()
    }

    @Async
    fun invalidateArticleCache() {
        log.debug { "Invalidating 'articles' cache." }
        cacheManager.getCache("articles")?.invalidate()
    }

    @Async
    fun invalidateAccountCache(username: String) {
        log.debug { "Invalidating 'account' cache." }
        cacheManager.getCache("account")?.evict(username)
    }

    @Async
    fun invalidateSelectLatestValidGrades(courseExerciseId: Long) {
        log.debug { "Invalidating 'selectLatestValidGrades' cache." }
        cacheManager.getCache("selectLatestValidGrades")?.evict(courseExerciseId)
    }

    @Async
    fun invalidateSelectLatestValidGrades() {
        log.debug { "Invalidating 'selectLatestValidGrades' cache." }
        cacheManager.getCache("selectLatestValidGrades")?.invalidate()
    }

    @Async
    fun invalidateAccountCache() {
        log.debug { "Invalidating 'account' cache." }
        cacheManager.getCache("account")?.invalidate()
    }
}