package core.ems.service

import mu.KotlinLogging
import org.springframework.cache.annotation.CacheEvict
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}


@Component
class CacheInvalidator {

    @CacheEvict("submissions", allEntries = true, beforeInvocation = false)
    fun invalidateSubmissionCache() = log.debug { "Invalidating submission cache." }

    @CacheEvict("autoassessment", allEntries = true, beforeInvocation = false)
    fun invalidateAutoAssessmentCountCache() = log.debug { "Invalidating assessment cache." }

    @CacheEvict("users", allEntries = true, beforeInvocation = false)
    fun invalidateTotalUserCache() = log.debug { "Invalidating total number of users cache." }

    @CacheEvict("articles", allEntries = true, beforeInvocation = false)
    fun invalidateArticleCache() = log.debug { "Invalidating article cache." }
}