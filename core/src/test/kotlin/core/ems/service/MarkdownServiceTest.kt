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

        // The canary tag. It is in the survival half because the failure that made it necessary is
        // exactly the one this half exists to catch: an unsafelisted tag is unwrapped and its text
        // kept, so a canary would not vanish — it would appear, in front of the class.
        "<easy-hidden>salajane</easy-hidden>" to "<easy-hidden",

        // Raw HTML the existing corpus actually contains. The collapsible hint block is the one
        // worth naming: it is the standard way an exercise hides its answer, and markdown has no
        // syntax for it, so it is only ever written as raw HTML.
        "<details><summary>Hint</summary>\n\nAnswer.\n\n</details>" to "<summary>Hint</summary>",
        "<div class=\"paragraph\">\n<p>x</p>\n</div>" to "<div class=\"paragraph\">",
        "<figure><img src=\"/v2/resource/k/p.png\" alt=\"x\" width=\"300\"><figcaption>c</figcaption></figure>"
                to "width=\"300\"",
        "<table><colgroup><col span=\"2\"></colgroup><tr><td>a</td></tr></table>" to "<col span=\"2\">",
        // Proportional table columns are written as `style` on `col`, and it is the one place `style`
        // survives. CSS applies only border, background, width and visibility to a table column, so
        // the overlay `style` is otherwise excluded for cannot be expressed here.
        "<table><colgroup><col style=\"width: 33.3333%;\"></colgroup><tr><td>a</td></tr></table>"
                to "style=\"width: 33.3333%;\"",
        // A lettered list. Without `type` this silently renumbers itself 1, 2, 3.
        "<ol type=\"a\"><li>first</li></ol>" to "<ol type=\"a\">",
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
    fun `every tag and attribute pair the stored corpus contains survives`() {
        // This list is not invented. Every (tag, attribute) pair present in the six stored rich-text
        // columns was extracted and checked against the safelist, and this is that list — so a
        // failure here means the safelist got tighter than content that already exists.
        //
        // Two pairs from that extraction are deliberately absent and are asserted below instead:
        // `style` on anything but `col`, and nothing else. Everything here must round-trip.
        val corpus = """
            <div class="paragraph" id="d1" role="doc-part" data-wrapper="x"><p class="p">t</p></div>
            <a href="/x" class="bare" id="a1" lang="et">l</a>
            <pre class="highlightjs highlight"><code class="language-python" data-lang="python">x</code></pre>
            <h1 class="sect0" id="h1">a</h1><h2 id="h2">b</h2><h3 id="h3">c</h3>
            <h4 id="h4">d</h4><h5 id="h5">e</h5>
            <img src="/v2/resource/k/p.png" alt="a" width="300" height="200" title="t" role="img">
            <ol class="olist arabic" start="3" type="a"><li>x</li></ol>
            <ul class="ulist"><li>y</li></ul>
            <table class="tableblock" role="grid">
              <!-- inside the table, because a caption anywhere else is invalid HTML and the parser
                   discards it before the safelist is consulted -->
              <caption class="title">c</caption>
              <colgroup><col span="2" width="50" style="width: 33.3333%;"></colgroup>
              <thead><tr><th class="tableblock" colspan="2" scope="col">h</th></tr></thead>
              <tbody><tr><td class="tableblock" rowspan="1">d</td></tr></tbody>
            </table>
            <dl class="dlist"><dt class="hdlist1">term</dt><dd>def</dd></dl>
            <details open><summary class="title">Hint</summary><p>a</p></details>
            <figure><img src="/x.png" alt="f"><figcaption>cap</figcaption></figure>
            <span class="bold" role="note">s</span><mark>m</mark><sub>2</sub><sup>3</sup>
            <b>b</b><i>i</i><hr><br><blockquote class="quoteblock">q</blockquote>
        """.trimIndent()

        val html = service.mdToHtml(corpus)

        // Tags: everything the corpus contains, none of which may be dropped.
        listOf(
            "div", "a", "caption", "pre", "code", "h1", "h2", "h3", "h4", "h5", "img", "ol", "ul",
            "li", "table", "colgroup", "col", "thead", "tr", "th", "tbody", "td", "dl", "dt", "dd",
            "details", "summary", "figure", "figcaption", "span", "mark", "sub", "sup", "b", "i",
            "hr", "br", "blockquote", "p",
        ).forEach { tag -> assertTrue(html.contains("<$tag"), "lost <$tag>: $html") }

        // Attributes, by the pair that carries them.
        listOf(
            "class=\"paragraph\"", "id=\"d1\"", "role=\"doc-part\"", "data-wrapper=\"x\"",
            "href=\"/x\"", "lang=\"et\"", "data-lang=\"python\"", "class=\"language-python\"",
            "alt=\"a\"", "width=\"300\"", "height=\"200\"", "title=\"t\"",
            "start=\"3\"", "type=\"a\"",
            "span=\"2\"", "style=\"width: 33.3333%;\"", "colspan=\"2\"", "scope=\"col\"",
            "rowspan=\"1\"", "open",
        ).forEach { attr -> assertTrue(html.contains(attr), "lost $attr: $html") }
    }

    @Test
    fun `style survives on col and nowhere else`() {
        // The exemption, and its boundary. Both halves matter: dropping the first breaks every
        // proportional table, and widening it past `col` reinstates the overlay the ban is for.
        // `easy-hidden` also carries a `style`, but never the author's: see [concealHiddenText].
        val html = service.mdToHtml(
            """
            <table><colgroup><col style="width: 50%;"></colgroup>
            <tr><td style="padding: 2em">a</td><th style="padding: 2em">b</th></tr></table>
            <span style="position: fixed; inset: 0">s</span>
            <p style="color: red">p</p>
            <img src="/x.png" alt="i" style="position: fixed">
            """.trimIndent()
        )

        assertTrue(html.contains("style=\"width: 50%;\""), html)
        assertEquals(1, Regex("style=").findAll(html).count(), "style survived somewhere else: $html")
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
    fun `easy-hidden is hidden by us, in a way that survives being copied`() {
        // The mechanism: a student pastes the description into a chatbot and takes the canary with
        // them. `display: none` and `visibility: hidden` are not copied, so the declaration matters
        // as much as the fact that there is one.
        val html = service.mdToHtml("<easy-hidden>salajane</easy-hidden> nähtav")

        assertTrue(html.contains("<easy-hidden"), html)
        assertTrue(html.contains("salajane"), html)
        assertTrue(html.contains("clip-path:inset(50%)"), html)
        assertTrue(html.contains("aria-hidden=\"true\""), html)
        assertFalse(html.contains("display:none"), html)
        assertFalse(html.contains("visibility:hidden"), html)
    }

    @Test
    fun `a heading inside easy-hidden is hidden too, which inheritance alone would not manage`() {
        // The first version of the declaration was `font-size:0;color:transparent`, and it hid only
        // what inherits both. `proseStyles.ts` gives h4-h6 their own `fontSize` and `color`, so a
        // canary wrapping a heading rendered at full size. Clipping contains the subtree whatever it
        // declares — this test is here so nobody swaps the recipe back for a shorter one.
        val html = service.mdToHtml("<easy-hidden>\n\n#### Juhis\n\ntekst\n\n</easy-hidden>")

        assertTrue(html.contains("<h4>Juhis</h4>"), html)
        assertTrue(html.contains("clip-path:inset(50%)"), html)
        assertFalse(html.contains("font-size:0"), html)
    }

    @Test
    fun `a link inside easy-hidden leaves the tab order`() {
        // aria-hidden over a focusable element is its own accessibility fault: the key lands on a
        // link the screen reader will not announce.
        val html = service.mdToHtml("<easy-hidden>[klõpsa](https://example.org)</easy-hidden>")

        assertTrue(html.contains("tabindex=\"-1\""), html)
        assertTrue(html.contains("aria-hidden=\"true\""), html)
    }

    @Test
    fun `an author cannot decide what easy-hidden looks like`() {
        // The tag takes no attributes from the author: the safelist drops them, and the pass after
        // the clean writes ours. Without this an author could make the canary visible — or, worse,
        // make ordinary text invisible and hide the actual task.
        val html = service.mdToHtml(
            "<easy-hidden style=\"font-size:2em;color:red\" onclick=\"steal()\" class=\"x\">s</easy-hidden>"
        )

        assertFalse(html.contains("2em"), html)
        assertFalse(html.contains("red"), html)
        assertFalse(html.contains("onclick"), html)
        assertTrue(html.contains("clip-path:inset(50%)"), html)
        // `class` is allowed on every tag and survives here too, which is deliberate and inert: an
        // author's class can only do something where `web/` already cooperates, and it cannot undo
        // the hiding, because an inline declaration beats any stylesheet rule it could name.
        assertTrue(html.contains("class=\"x\""), html)
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
