package core.ems.service.cache

import core.conf.security.EasyUser
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminEvictAllCacheController(private val cachingService: CachingService) {

    @Secured("ROLE_ADMIN")
    @PostMapping("/remove-cache")
    fun controller(caller: EasyUser) {
        log.debug { "${caller.id} is calling cache eviction service to force evict all cached content." }
        cachingService.invalidateAll()
    }
}
