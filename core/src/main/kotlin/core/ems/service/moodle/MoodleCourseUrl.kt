package core.ems.service.moodle

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets


/**
 * Where a course lives in Moodle, for linking a person there from the sidebar (EZ-1874).
 *
 * Moodle's `course/view.php` resolves a course by **shortname** and not only by its numeric id —
 * `?name=<shortname>` is checked before `idnumber` and `id`, then looked up `MUST_EXIST`, and the
 * page redirects to the canonical `?id=` URL. Which is the whole reason this feature is a config
 * entry and a string concatenation rather than a Moodle plugin change: `course.moodle_short_name`
 * is already stored, so nothing needs to fetch, store or migrate a course id.
 *
 * ### Why the URL is not derived from the sync URL
 *
 * There is already a Moodle address in the config — `easy.core.moodle-sync.users.url` — and taking
 * its origin would be wrong, not merely inelegant. Dev pins both sync URLs to
 * `http://127.0.0.1:9/moodle-disabled-on-dev` so that no test can write into a real gradebook, and
 * deriving would put `http://127.0.0.1:9/course/view.php?name=…` in dev's sidebar. Reading a course
 * page is also a different permission from writing grades into one, so an environment may
 * reasonably want this without the other.
 *
 * ### Why a whole prefix rather than a hostname
 *
 * So that no Kotlin here has to know Moodle's URL shape. Whoever configures it pastes what their
 * Moodle actually serves, up to and including `?name=`, and this class appends one encoded value.
 * A Moodle that changes its course URLs is then a config edit rather than a release.
 *
 * Empty is the default and means **no link anywhere** — an environment that has nowhere to send a
 * teacher shows one sidebar item fewer, which is how the frontend's own optional links
 * (`idpAdminUrl`, `bugReportDashboardUrl`) already behave.
 */
@Service
class MoodleCourseUrl {
    private val log = KotlinLogging.logger {}

    @Value($$"${easy.core.moodle-sync.course-url-prefix:}")
    private lateinit var prefix: String

    /**
     * The Moodle course page for this shortname, or null if there is nothing to link to — either the
     * course is not Moodle-linked, or this environment has no Moodle to point at.
     *
     * Percent-encoded as a query parameter, because shortnames are Moodle's to choose and carry
     * spaces, dots and parens. `encodeQueryParam` and not [java.net.URLEncoder]: the latter spells a
     * space `+`, which is only correct inside a query string, and the prefix is the operator's to
     * write — a `%20` is right wherever they put the value.
     */
    fun urlFor(moodleShortName: String?): String? {
        if (prefix.isBlank() || moodleShortName.isNullOrBlank()) return null
        val url = prefix.trim() + UriUtils.encodeQueryParam(moodleShortName, StandardCharsets.UTF_8)
        log.trace { "Moodle course URL for shortname '$moodleShortName': $url" }
        return url
    }
}
