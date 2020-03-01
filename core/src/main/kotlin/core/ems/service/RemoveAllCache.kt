package core.ems.service

import core.conf.security.EasyUser
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveAllCacheController(private val cacheInvalidator: CacheInvalidator) {

    @Secured("ROLE_ADMIN")
    @PostMapping("/remove-cache")
    fun controller(caller: EasyUser) {
        log.debug { "${caller.id} is evicting all cache" }

        cacheInvalidator.invalidateSelectLatestValidGrades()
        cacheInvalidator.invalidateAccountCache()
        cacheInvalidator.invalidateArticleCache()
    }
}
