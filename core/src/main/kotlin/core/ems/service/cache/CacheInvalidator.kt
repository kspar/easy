package core.ems.service.cache

import mu.KotlinLogging
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}


@Component
class CacheInvalidator(val cacheManager: CacheManager) {

    @CacheEvict("submissions", allEntries = true, beforeInvocation = false)
    fun invalidateSubmissionCache() = log.debug { "Invalidating submission cache." }

    @CacheEvict("autoassessment", allEntries = true, beforeInvocation = false)
    fun invalidateAutoAssessmentCountCache() = log.debug { "Invalidating assessment cache." }

    @CacheEvict("users", allEntries = true, beforeInvocation = false)
    fun invalidateTotalUserCache() = log.debug { "Invalidating total number of users cache." }

    @CacheEvict("articles", allEntries = true, beforeInvocation = false)
    fun invalidateArticleCache() = log.debug { "Invalidating article cache." }

    fun invalidateAccountCache(username: String) {
        log.debug { "Invalidating 'account' cache." }
        cacheManager.getCache("account")?.evict(username)
    }

    fun invalidateSelectLatestValidGrades(courseExerciseId: Long) {
        log.debug { "Invalidating 'selectLatestValidGrades' cache." }
        cacheManager.getCache("selectLatestValidGrades")?.evict(courseExerciseId)
    }

    @CacheEvict("selectLatestValidGrades", allEntries = true, beforeInvocation = false)
    fun invalidateSelectLatestValidGrades() = log.debug { "Invalidating all 'selectLatestValidGrades' cache." }

    @CacheEvict("account", allEntries = true, beforeInvocation = false)
    fun invalidateAccountCache() = log.debug { "Invalidating 'account' cache." }
}