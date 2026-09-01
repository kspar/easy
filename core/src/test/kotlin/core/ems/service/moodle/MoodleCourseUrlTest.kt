package core.ems.service.moodle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils

/**
 * The Moodle course link the sidebar shows on a Moodle-linked course (EZ-1874).
 *
 * Two things can go wrong here and neither reports itself. **An absent link looks like a feature
 * that was never built**: an environment with no prefix configured, or a course with no shortname,
 * must produce null and nothing else — and a null is exactly what a missing implementation also
 * produces, so the positive cases below are what separate the two. And **a wrong link looks like
 * Moodle's fault**: a shortname carrying a space or an ampersand, unencoded, arrives at
 * `course/view.php` as a different shortname or as a second query parameter, and the person who
 * clicks it gets Moodle's "course does not exist" page with no hint that Lahendus wrote the URL.
 *
 * Unit and not integration, like [MoodleCourseAllowlistTest] alongside it: `@Value` binding is
 * Spring's and tested by Spring. What is ours is the null-vs-URL decision and the encoding.
 */
class MoodleCourseUrlTest {

    private val prefix = "https://moodle.example/course/view.php?name="

    private fun urlBuilderWith(prefix: String) = MoodleCourseUrl().also {
        ReflectionTestUtils.setField(it, "prefix", prefix)
    }

    // --- nothing to link to -----------------------------------------------------------------------

    @Test
    fun `no configured prefix means no link`() {
        // The default, and what a laptop runs with: no Moodle to send anyone to, so no sidebar item
        // rather than a link to nowhere.
        assertNull(urlBuilderWith("").urlFor("LTAT.03.001"))
        assertNull(urlBuilderWith("   ").urlFor("LTAT.03.001"))
    }

    @Test
    fun `a course with no shortname has no link even where Moodle is configured`() {
        val urls = urlBuilderWith(prefix)
        assertNull(urls.urlFor(null))
        assertNull(urls.urlFor(""))
        // Blank and not merely empty: `moodle_short_name` is nullable text with no constraint, and
        // the sync treats a blank one as unlinked too (`selectCourseShortName` callers check
        // `isNullOrBlank`). A link to `?name=%20` would be a link to Moodle's error page.
        assertNull(urls.urlFor("  "))
    }

    // --- the link itself --------------------------------------------------------------------------

    @Test
    fun `a shortname is appended to the prefix`() {
        assertEquals(
            "https://moodle.example/course/view.php?name=LTAT.03.001",
            urlBuilderWith(prefix).urlFor("LTAT.03.001")
        )
    }

    @Test
    fun `the prefix is used as written, whatever URL shape it describes`() {
        // The point of configuring a whole prefix: core knows nothing about Moodle's URLs, so an
        // instance that addresses courses differently needs a config edit and no code.
        assertEquals(
            "https://moodle.example/kursus/nimi/DEMO",
            urlBuilderWith("https://moodle.example/kursus/nimi/").urlFor("DEMO")
        )
    }

    @Test
    fun `surrounding whitespace in the configured prefix is dropped`() {
        // A YAML value with a trailing space is invisible in a diff and would otherwise put a space
        // in the middle of an href.
        assertEquals(
            "https://moodle.example/course/view.php?name=DEMO",
            urlBuilderWith("  $prefix  ").urlFor("DEMO")
        )
    }

    // --- encoding ---------------------------------------------------------------------------------

    @Test
    fun `a shortname with a space is percent-encoded, not plus-encoded`() {
        // %20 and not `+`: the prefix is the operator's to write, and `+` only means a space inside a
        // query string. This is why URLEncoder is not used here.
        assertEquals(
            "https://moodle.example/course/view.php?name=Programmeerimine%20I",
            urlBuilderWith(prefix).urlFor("Programmeerimine I")
        )
    }

    @Test
    fun `an ampersand in a shortname cannot become a second query parameter`() {
        // The one that would be a bug rather than a broken link: unencoded, everything after the `&`
        // reaches Moodle as its own parameter and the course lookup silently uses a shorter name.
        assertEquals(
            "https://moodle.example/course/view.php?name=A%26B",
            urlBuilderWith(prefix).urlFor("A&B")
        )
    }

    @Test
    fun `an Estonian shortname survives as UTF-8`() {
        assertEquals(
            "https://moodle.example/course/view.php?name=%C3%9Clesanded%20(2026)",
            urlBuilderWith(prefix).urlFor("Ülesanded (2026)")
        )
    }
}
