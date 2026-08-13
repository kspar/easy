package core.ems.service.article

import core.ems.service.cache.CachingService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/**
 * Reading a published article without an account.
 *
 * Articles are the FAQ and the guides — including the one about logging in, which is needed by
 * exactly the person who cannot. `/a/<alias>` therefore has to render with no session, and the
 * alias is short enough to write on a slide, which is the whole reason aliases exist (EZ-1572).
 *
 * **This controller cannot leak a draft, structurally rather than by being careful.** It holds no
 * caller object — an unauthenticated request has none to inject — so the only value it can pass for
 * `isAdmin` is the literal `false` below, and the visibility rule lives once, in
 * [CachingService.selectLatestArticleVersion]. An unpublished article is filtered out there and
 * answers exactly as a nonexistent one does.
 *
 * No `@Secured`, and the path must also be listed in `SecurityConf`'s permitAll matchers, or the
 * filter chain answers 401 before this is ever reached.
 */
@RestController
@RequestMapping("/v2")
class AnonymousReadArticleController(private val cachingService: CachingService) {
    private val log = KotlinLogging.logger {}

    @GetMapping("/unauth/articles/{articleId}")
    fun controller(@PathVariable("articleId") articleIdString: String): ReadArticleDetailsController.Resp {

        log.info { "Reading article '$articleIdString' anonymously" }
        return cachingService.selectLatestArticleVersion(articleIdString, isAdmin = false)
    }
}
