package core.ems.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Unit tests for [MarkdownService]. Context-free, so these run in CI — see the note in
 * `core/conf/security/EasyUserJwtConverterTest`.
 *
 * The suite has two halves and needs both, because the sanitiser can fail in two opposite ways and
 * only one of them is loud.
 *
 *  - [markupSurvives] is the half that would otherwise be missing. A tag left out of the safelist
 *    does not throw and does not fail a build; the content simply stops rendering, in an exercise
 *    nobody is looking at. Every construct our renderer can emit, and every kind of raw HTML the
 *    real corpus contains, is listed here so that tightening the safelist has to be a decision.
 *  - [payloadsAreNeutralised] is the half that says the cleaner is doing something. Each case is
 *    written as an assertion about what must *not* be in the output.
 *
 * A note on why the second half is spelled out at this length rather than trusting the library. The
 * step before this one, which added `target` and `rel` to anchors, also round-tripped through jsoup,
 * and reading the code it was easy to believe that round trip was sanitising something. It was not.
 * A `<script>` payload written on its own line came back empty — not because it was cleaned, but
 * because `Jsoup.parse` relocates a script into `<head>` and the code returned `body().html()`. Put
 * any text in front of the same payload and it survived intact. So the one payload a reviewer is
 * most likely to try was the one payload that gave a false negative, and the cases below therefore
 * include the same payload alone, after text, inline, and inside a table cell.
 */
class MarkdownServiceTest {

    private val service = MarkdownService()

    /**
     * Markdown in, and a fragment that must appear in the rendered HTML. If one of these starts
     * failing, the safelist got tighter than the content it has to render.
     */
    @TestFactory
    fun markupSurvives() = listOf(
        // Everything commonmark and the two extensions emit.
        "# Heading" to "<h1>Heading</h1>",
        "###### Deep" to "<h6>Deep</h6>",
        "*em*" to "<em>em</em>",
        "**strong**" to "<strong>strong</strong>",
        "~~struck~~" to "<del>struck</del>",
        "`inline`" to "<code>inline</code>",
        "before\n\n---\n\nafter" to "<hr>",
        "line one  \nline two" to "<br>",
        "```\nprint(1)\n```" to "<pre><code>print(1)",
        // The highlighter's only input. Dropping `class` from the safelist would leave every code
        // block on the site unhighlighted and nothing would fail.
        "```python\nprint(1)\n```" to "<code class=\"language-python\">",
        "> quoted" to "<blockquote>",
        "- a\n- b" to "<li>a</li>",
        "3. a" to "<ol start=\"3\">",
        "[t](https://example.org/x)" to "href=\"https://example.org/x\"",
        "[t](https://example.org \"the title\")" to "title=\"the title\"",
        "[m](mailto:a@b.example)" to "href=\"mailto:a@b.example\"",
        "<https://example.org>" to "href=\"https://example.org\"",
        "![alt](https://example.org/i.png)" to "<img src=\"https://example.org/i.png\" alt=\"alt\">",
        // Table alignment arrives as an `align` attribute on every cell, not as a class.
        "| a |\n|:-:|\n| 1 |" to "<th align=\"center\">a</th>",

        // Relative URLs. These are the case a safelist gets wrong by default: jsoup checks the
        // protocol allowlist against the absolutised URL, so with no base URI these lose the
        // attribute outright. `/v2/resource/` is how every uploaded image is addressed, so getting
        // this wrong would blank the images in a large part of the library.
        "![alt](/v2/resource/abc123/pic.png)" to "src=\"/v2/resource/abc123/pic.png\"",
        "[t](/library/exercise/4)" to "href=\"/library/exercise/4\"",
        "[t](sub/page.html)" to "href=\"sub/page.html\"",
        "[t](#section)" to "href=\"#section\"",
        // A few exercises inline a small image rather than uploading it. `data:` is allowed on
        // `img[src]` and nowhere else.
        "![alt](data:image/png;base64,iVBORw0KGgo=)" to "src=\"data:image/png;base64,iVBORw0KGgo=\"",

        // Raw HTML the existing corpus actually contains. The collapsible hint block is the one
        // worth naming: it is the standard way an exercise hides its answer, and markdown has no
        // syntax for it, so it is only ever written as raw HTML.
        "<details><summary>Hint</summary>\n\nAnswer.\n\n</details>" to "<summary>Hint</summary>",
        "<div class=\"paragraph\">\n<p>x</p>\n</div>" to "<div class=\"paragraph\">",
        "<figure><img src=\"/v2/resource/k/p.png\" alt=\"x\" width=\"300\"><figcaption>c</figcaption></figure>"
                to "width=\"300\"",
        "<table><colgroup><col span=\"2\"></colgroup><tr><td>a</td></tr></table>" to "<col span=\"2\">",
        "<dl><dt>term</dt><dd>def</dd></dl>" to "<dt>term</dt>",
        "H<sub>2</sub>O" to "<sub>2</sub>",
        "x<sup>2</sup>" to "<sup>2</sup>",
        "<mark>hi</mark>" to "<mark>hi</mark>",
        "<kbd>Ctrl</kbd>" to "<kbd>Ctrl</kbd>",
        "<abbr title=\"HyperText Markup Language\">HTML</abbr>" to "<abbr title=\"HyperText",
        // The asciidoc era anchored cross-references with `<a name>`, not `id`. `href="#section"`
        // above tests the link; this tests the thing it lands on.
        "<a name=\"sec1\"></a>Section" to "name=\"sec1\"",
        "<pre class=\"highlightjs highlight\"><code data-lang=\"python\">x</code></pre>" to "data-lang=\"python\"",
        // The maths extension's output shape (EZ-1732), written as raw HTML so that this pins the
        // safelist on a branch where the extension itself does not exist yet. Stripping the attribute
        // would leave KaTeX an empty formula and fail nothing.
        "<span class=\"easy-math\" data-easy-math=\"inline\" data-easy-tex=\"x^2\">\$x^2\$</span>"
                to "data-easy-tex=\"x^2\"",
    ).map { (md, expected) ->
        DynamicTest.dynamicTest(md.lineSequence().first().take(60)) {
            val html = service.mdToHtml(md)
            assertTrue(html.contains(expected), "expected $expected in: $html")
        }
    }

    /**
     * Markdown in, and a fragment that must NOT survive into the rendered HTML.
     *
     * Every case here was verified to survive the pipeline as it stood before the safelist was
     * added, so each one is a test that can fail rather than a test of something already impossible.
     */
    @TestFactory
    fun payloadsAreNeutralised() = listOf(
        Triple("img with an error handler", "<img src=x onerror=alert(document.domain)>", "onerror"),
        Triple("svg with a load handler", "<svg onload=alert(1)></svg>", "onload"),
        Triple("details with a toggle handler", "<details open ontoggle=alert(1)>x</details>", "ontoggle"),
        Triple("anchor with a click handler", "<a href=\"https://x.example\" onclick=\"alert(1)\">x</a>", "onclick"),
        Triple("javascript: link", "[click](javascript:alert(document.domain))", "javascript:"),
        Triple("javascript: link, mixed case", "[click](JaVaScRiPt:alert(1))", "avaScript:"),
        Triple("javascript: link, split by a tab entity", "<a href=\"java&#9;script:alert(1)\">x</a>", "script:alert"),
        Triple("data:text/html link", "[click](data:text/html;base64,PHNjcmlwdD4=)", "data:text/html"),
        Triple("javascript: image source", "<img src=\"javascript:alert(1)\">", "javascript:"),
        Triple("iframe with srcdoc", "<iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"></iframe>", "<iframe"),
        Triple("credential-collecting form", "<form action=\"https://evil.example\"><input name=pw></form>", "<form"),
        Triple("style element", "Text.\n\n<style>body{display:none}</style>", "<style"),
        Triple("object element", "<object data=\"https://evil.example/x\"></object>", "<object"),
        Triple("base element", "Text.\n\n<base href=\"https://evil.example/\">", "<base"),
        Triple("meta refresh", "Text.\n\n<meta http-equiv=refresh content=\"0;url=https://e.example\">", "<meta"),
        Triple("style attribute", "<span style=\"position:fixed;top:0\">x</span>", "style="),
        // The four positions of the same payload. Before the safelist, the first of these came back
        // empty and the other three did not — see the class KDoc.
        Triple("script alone", "<script>alert(1)</script>", "<script"),
        Triple("script after a paragraph", "Exercise text.\n\n<script>alert(1)</script>", "<script"),
        Triple("script inline in a sentence", "Solve this <script>alert(1)</script> please", "<script"),
        Triple("script inside a table cell", "| a |\n|---|\n| <script>alert(1)</script> |", "<script"),
        // Markup that browsers re-parse in a foreign content mode, which is how a cleaner that only
        // filters tag names by name gets walked around.
        Triple("mglyph/style mutation", "<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>", "onerror"),
        Triple("svg inside an allowed element", "<details><summary><svg onload=alert(1)></svg></summary></details>", "onload"),
    ).map { (name, md, forbidden) ->
        DynamicTest.dynamicTest(name) {
            val html = service.mdToHtml(md)
            assertFalse(html.contains(forbidden, ignoreCase = true), "expected no $forbidden in: $html")
        }
    }

    @Test
    fun `text is escaped rather than dropped, so an exercise about HTML still reads correctly`() {
        // The point of a safelist over `escapeHtml(true)`: markup is removed, but text that merely
        // looks like markup is escaped and still renders. A programming exercise is full of both.
        val html = service.mdToHtml("Plain & <b>bold</b>, and 5 < 6")
        assertTrue(html.contains("<b>bold</b>"), html)
        assertTrue(html.contains("5 &lt; 6"), html)
        assertTrue(html.contains("Plain &amp;"), html)
    }

    @Test
    fun `a fenced code block showing HTML keeps showing it`() {
        // The corpus is a programming course: `<div>` inside a fence is teaching material, and
        // commonmark escapes it before the cleaner ever sees a tag. If this ever starts failing, the
        // sanitiser has been moved to the wrong side of the renderer.
        val html = service.mdToHtml("```html\n<div onclick=\"x()\">hi</div>\n```")
        assertTrue(html.contains("&lt;div onclick=\"x()\"&gt;"), html)
    }

    @Test
    fun `every anchor is externalised, whatever the author asked for`() {
        val html = service.mdToHtml("<a href=\"https://x.example\" target=\"_self\" rel=\"opener\">x</a>")
        assertTrue(html.contains("target=\"_blank\""), html)
        assertTrue(html.contains("rel=\"noopener noreferrer\""), html)
        assertFalse(html.contains("_self"), html)
        assertFalse(html.contains("\"opener\""), html)
    }

    @Test
    fun `a removed element leaves its text behind, which is a rendering artefact and not a hole`() {
        // jsoup's cleaner drops the element and keeps its text nodes, so the label on a stripped
        // button survives as prose. Pinned rather than fixed: it is how a form-shaped block will
        // actually look after this change, so anyone reading a slightly odd exercise text can find
        // the explanation here instead of suspecting the sanitiser of half-working.
        val html = service.mdToHtml("<form action=\"https://evil.example\"><button>Log in</button></form>")
        assertFalse(html.contains("<form"), html)
        assertFalse(html.contains("<button"), html)
        assertTrue(html.contains("Log in"), html)
    }

    @Test
    fun `the placeholder base URI never reaches the output`() {
        // It exists only so the protocol check has something to resolve against. If it ever shows up
        // in stored HTML, `preserveRelativeLinks` has been turned off and every relative link in the
        // library has been rewritten to point at a domain that does not exist.
        val html = service.mdToHtml("[t](/library/exercise/4)\n\n![i](/v2/resource/k/p.png)")
        assertFalse(html.contains("easy.invalid"), html)
    }

    @Test
    fun `the cleaner reaches a fixed point on its own output`() {
        // Feeding the rendered HTML back in is a fair second pass, because commonmark hands raw HTML
        // to the cleaner unchanged. If a second pass differed from the first, the cleaner would be
        // rewriting markup into something it then treats differently — the shape a mutation-XSS bug
        // takes, and also the shape of content that erodes a little on every edit.
        val md = "# T\n\n<details><summary>Hint</summary>\n\n![i](/v2/resource/k/p.png)\n\n</details>"
        val once = service.mdToHtml(md)
        assertEquals(once, service.mdToHtml(once))
    }
}
