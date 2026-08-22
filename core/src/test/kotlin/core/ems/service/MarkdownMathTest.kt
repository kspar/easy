package core.ems.service

import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [MathExtension] through the real [MarkdownService], because the thing worth testing is not the
 * parser in isolation — it is what a teacher's `$…$` looks like after the whole pipeline, Jsoup
 * pretty-printer included. Context-free, so these run in CI.
 *
 * Assertions read `data-easy-tex` rather than the rendered string, since that attribute is the
 * contract with `web/src/components/markdown/renderMath.ts`. Comparing HTML text would pass while
 * the client got nothing.
 */
class MarkdownMathTest {

    private val md = MarkdownService()

    private fun html(source: String) = md.mdToHtml(source)

    /** Every `data-easy-tex` in document order, paired with its mode. */
    private fun maths(source: String): List<Pair<String, String>> =
        Jsoup.parseBodyFragment(html(source)).select("[data-easy-tex]")
            .map { it.attr("data-easy-math") to it.attr("data-easy-tex") }

    private fun tex(source: String): List<String> = maths(source).map { it.second }


    // --- The point of the exercise: TeX that Markdown would otherwise eat -------------------------

    /**
     * The case that makes this a parser extension instead of a client-side scan. Before EZ-1732 the
     * asterisks became an `<em>` and the multiplication was gone from the HTML for good.
     */
    @Test
    fun `emphasis characters survive inside maths`() {
        assertEquals(listOf("a * b * c"), tex("Compute \$a * b * c\$ please."))
        assertFalse(html("Compute \$a * b * c\$ please.").contains("<em>"))
    }

    @Test
    fun `underscores survive, including the spaced ones CommonMark would pair up`() {
        assertEquals(listOf("x_1 + x_2"), tex("\$x_1 + x_2\$"))
        // Intraword `_` is disabled in CommonMark, so `x_1 + x_2` was safe by accident. This one
        // was not: spaces around the underscores make them ordinary emphasis delimiters.
        assertEquals(listOf("a _ b _ c"), tex("\$a _ b _ c\$"))
        assertFalse(html("\$a _ b _ c\$").contains("<em>"))
    }

    /**
     * A TeX line break followed by a macro. Markdown reads `\\` as an escaped backslash, so this
     * used to reach the browser as `\alpha` — the break silently deleted. It is the failure the
     * adoc→md migration needed a "dangling-backslash fix" for.
     */
    @Test
    fun `a double backslash is not an escape inside maths`() {
        // Plain strings, not raw ones: `$` interpolates in a raw string too, and `$` is the
        // character under test.
        assertEquals(listOf("a \\\\ \\alpha"), tex("\$a \\\\ \\alpha\$"))
    }

    @Test
    fun `backticks inside maths do not open a code span`() {
        assertEquals(listOf("a ` b"), tex("\$a ` b\$"))
        assertFalse(html("\$a ` b\$").contains("<code>"))
    }


    // --- Inline -----------------------------------------------------------------------------------

    @Test
    fun `inline maths becomes a span carrying its TeX and its mode`() {
        assertEquals(listOf("inline" to "x^2"), maths("The area is \$x^2\$ here."))
        val el = Jsoup.parseBodyFragment(html("\$x^2\$")).selectFirst("[data-easy-tex]")!!
        assertEquals("span", el.tagName())
        assertEquals("easy-math", el.className())
    }

    /**
     * The delimiters are kept in the element text on purpose: it is what a reader sees if KaTeX
     * fails to load, and that is exactly the pre-EZ-1732 behaviour rather than a blank gap.
     */
    @Test
    fun `the element text is the original source, delimiters and all`() {
        assertEquals("\$x^2\$", Jsoup.parseBodyFragment(html("\$x^2\$")).selectFirst("span")!!.text())
    }

    @Test
    fun `several formulae in one paragraph are separate elements`() {
        assertEquals(listOf("a", "b"), tex("First \$a\$ then \$b\$."))
    }

    @Test
    fun `maths spanning a soft line break still parses`() {
        assertEquals(listOf("a +\nb"), tex("text \$a +\nb\$ more"))
    }

    @Test
    fun `dollar-dollar written mid-sentence is displayed maths but stays a span`() {
        // A `<div>` inside a `<p>` is invalid HTML and browsers un-nest it, taking the formula out
        // of the sentence it belonged to.
        assertEquals(listOf("display" to "x^2"), maths("Note that \$\$x^2\$\$ holds."))
        assertEquals("span", Jsoup.parseBodyFragment(html("Note \$\$x^2\$\$ holds.")).selectFirst("[data-easy-tex]")!!.tagName())
    }


    // --- Display blocks ---------------------------------------------------------------------------

    @Test
    fun `a fence on its own lines is a display div`() {
        val el = Jsoup.parseBodyFragment(html("\$\$\n\\frac{1}{2}\n\$\$")).selectFirst("[data-easy-tex]")!!
        assertEquals("div", el.tagName())
        assertEquals("display", el.attr("data-easy-math"))
        assertEquals("""\frac{1}{2}""", el.attr("data-easy-tex"))
    }

    @Test
    fun `a multi-line fence keeps its newlines, which an environment needs`() {
        assertEquals(
            listOf("\\begin{pmatrix}\na & b \\\\\nc & d\n\\end{pmatrix}"),
            tex("\$\$\n\\begin{pmatrix}\na & b \\\\\nc & d\n\\end{pmatrix}\n\$\$"),
        )
    }

    @Test
    fun `a whole line of dollar-dollar-x-dollar-dollar is a display div`() {
        assertEquals(listOf("display" to "E = mc^2"), maths("\$\$E = mc^2\$\$"))
    }

    /**
     * The one-liner with *anything underneath it*, which is the only shape that reaches
     * `tryContinue` — a one-liner alone in a document is finalised without it ever being called.
     * Every other one-liner case here was the document's last line, so the suite was blind to a
     * `BlockContinue.none()` NullPointerException that made `mdToHtml` throw: no preview, no save,
     * a 500 for any teacher who wrote a display formula with a sentence after it.
     */
    @Test
    fun `a one-liner with text underneath it renders, and does not throw`() {
        assertEquals(listOf("display" to "x^2"), maths("\$\$x^2\$\$\nAfterwards."))
        assertTrue(html("\$\$x^2\$\$\nAfterwards.").contains("Afterwards."))
        assertEquals(listOf("display" to "x^2"), maths("Before.\n\n\$\$x^2\$\$\n\n## Task\n\nWrite code."))
        assertTrue(html("Before.\n\n\$\$x^2\$\$\n\n## Task").contains("<h2>"))
    }

    /**
     * Interrupting a paragraph, like a fenced code block does. Without this, the way people
     * actually write — a lead-in line, then the formula, no blank line between — silently produces
     * literal dollars.
     */
    @Test
    fun `a fence interrupts an open paragraph`() {
        assertEquals(listOf("x^2"), tex("Consider:\n\$\$\nx^2\n\$\$"))
    }

    @Test
    fun `an unterminated fence closes at the end rather than dropping the formula`() {
        assertEquals(listOf("x^2"), tex("\$\$\nx^2"))
    }

    /**
     * An unterminated fence must not eat the rest of the exercise. A blank line is illegal in TeX
     * math mode, so ending the fence there costs nothing real — whereas running to the end of the
     * container turned every paragraph and heading below a mistyped `$$` into one formula, which
     * KaTeX then rendered as a single red block where the exercise used to be.
     */
    @Test
    fun `an unterminated fence stops at the blank line, not the end of the document`() {
        val source = "Consider:\n\$\$\nx^2\n\nAnd the rest of the exercise.\n\n## Task"
        assertEquals(listOf("x^2"), tex(source))
        assertTrue(html(source).contains("And the rest of the exercise."))
        assertTrue(html(source).contains("<h2>"))
    }

    @Test
    fun `two one-liners on one line are two inline formulae, not one mangled block`() {
        assertEquals(listOf("display" to "a", "display" to "b"), maths("\$\$a\$\$ and \$\$b\$\$"))
    }


    // --- Inside the constructs exercises are actually made of --------------------------------------

    @Test
    fun `inline maths works inside a list item`() {
        assertEquals(listOf("a^2", "b^2"), tex("- first \$a^2\$\n- second \$b^2\$"))
    }

    /**
     * Block starts are attempted after the list marker is consumed, so a fence indented to the item's
     * content column is a fence and not an indented code block. Worth pinning: "steps, one of which
     * is a formula" is the shape half these exercises are in.
     */
    @Test
    fun `a fence works inside a list item`() {
        assertEquals(listOf("display" to "x^2"), maths("- step one\n\n  \$\$\n  x^2\n  \$\$"))
    }

    @Test
    fun `inline maths works inside a table cell and a heading`() {
        assertEquals(listOf("a", "b"), tex("| x | y |\n| - | - |\n| \$a\$ | \$b\$ |"))
        assertEquals(listOf("n^2"), tex("## Complexity of \$n^2\$"))
    }

    @Test
    fun `inline maths works inside a blockquote`() {
        assertEquals(listOf("e^{i\\pi}"), tex("> Recall \$e^{i\\pi}\$."))
    }


    // --- What must not become maths ---------------------------------------------------------------

    /**
     * The reason `$…$` needs heuristics at all. Left alone, the first `$` opens, the second closes,
     * and a price list turns into the formula "5 and ".
     */
    @Test
    fun `currency amounts are left alone`() {
        assertEquals(emptyList<String>(), tex("It costs \$5 and \$6 in total."))
        assertTrue(html("It costs \$5 and \$6.").contains("\$5"))
    }

    @Test
    fun `a dollar with a space after it does not open maths`() {
        assertEquals(emptyList<String>(), tex("\$ x \$"))
    }

    @Test
    fun `shell variables separated by whitespace or a digit are left alone`() {
        assertEquals(emptyList<String>(), tex("Set \$PATH ja \$HOME."))
        assertEquals(emptyList<String>(), tex("Between \$5-\$10."))
    }

    /**
     * The documented gap, pinned as it stands rather than as it should be: two shell variables
     * joined by punctuation have no whitespace and no digit to give them away, so they parse as a
     * formula. See MathExtension's KDoc for why the rules are not tightened further — the fix
     * rejects `$T$`, which is likelier on this platform. Change this test the day that trade-off
     * changes; do not let it drift silently.
     */
    @Test
    fun `two shell variables joined by punctuation are the known false positive`() {
        assertEquals(listOf("PATH:"), tex("Set \$PATH:\$HOME now."))
        // And the escape hatches both work.
        assertEquals(emptyList<String>(), tex("Set `\$PATH:\$HOME` now."))
        assertEquals(emptyList<String>(), tex("Set \\\$PATH:\\\$HOME now."))
    }

    @Test
    fun `a lone dollar is a lone dollar`() {
        assertEquals(emptyList<String>(), tex("Costs \$100."))
        assertEquals(emptyList<String>(), tex("A \$ sign."))
    }

    @Test
    fun `an escaped dollar is text and cannot open maths`() {
        assertEquals(emptyList<String>(), tex("\\\$x^2\\\$"))
        assertTrue(Jsoup.parseBodyFragment(html("\\\$x^2\\\$")).text().contains("\$x^2\$"))
    }

    @Test
    fun `maths is not parsed inside a code span`() {
        assertEquals(emptyList<String>(), tex("Write `\$x^2\$` to typeset it."))
        assertTrue(html("Write `\$x^2\$` to typeset it.").contains("<code>"))
    }

    @Test
    fun `maths is not parsed inside a fenced code block`() {
        val source = "```\n\$\$\nx^2\n\$\$\n```"
        assertEquals(emptyList<String>(), tex(source))
        assertTrue(html(source).contains("<pre>"))
    }

    @Test
    fun `an indented fence is an indented code block, so a formula can be shown as source`() {
        assertEquals(emptyList<String>(), tex("    \$\$\n    x^2\n    \$\$"))
    }

    @Test
    fun `an empty pair of delimiters is not maths`() {
        assertEquals(emptyList<String>(), tex("\$\$"))
        assertEquals(emptyList<String>(), tex("\$\$\$\$"))
    }

    /**
     * `$$ $$` used to pass every guard, produce a block with no TeX, and then be dropped by the
     * renderer — so the line the author typed disappeared from the page entirely. Vanishing is the
     * one outcome worse than rendering nothing, because nobody can see that it happened.
     */
    @Test
    fun `a whitespace-only one-liner survives as text rather than vanishing`() {
        assertEquals(emptyList<String>(), tex("before\n\n\$\$ \$\$"))
        assertTrue(Jsoup.parseBodyFragment(html("before\n\n\$\$ \$\$")).text().contains("\$\$"))
    }

    @Test
    fun `three or more dollars is not a delimiter`() {
        assertEquals(emptyList<String>(), tex("\$\$\$x\$\$\$"))
    }


    // --- Safety -----------------------------------------------------------------------------------

    /**
     * The TeX is attacker-controlled — a teacher writes it, and on some courses so does a student
     * in feedback. It reaches the browser as an attribute value and must stay one; the client's
     * KaTeX call runs with `trust: false` so `\href` and `\includegraphics` cannot bite either.
     */
    @Test
    fun `TeX cannot break out of its attribute`() {
        val source = "\$\" onmouseover=\"alert(1)\" x=\"\$"
        val el = Jsoup.parseBodyFragment(html(source)).selectFirst("[data-easy-tex]")!!
        // The quote survives as `&quot;` in the attribute, so the TeX arrives verbatim and the
        // handler never becomes an attribute of its own. A grep for the literal string would pass
        // for the wrong reason: the element *text* carries an unescaped quote too, harmlessly,
        // because a quote in a text node is just a quote.
        assertFalse(el.hasAttr("onmouseover"), el.outerHtml())
        assertEquals("\" onmouseover=\"alert(1)\" x=\"", el.attr("data-easy-tex"))
    }

    @Test
    fun `TeX cannot close its own element and open a new one`() {
        val source = "\$</span><img src=x onerror=alert(1)>\$"
        val doc = Jsoup.parseBodyFragment(html(source))
        assertTrue(doc.select("img").isEmpty(), doc.body().html())
        assertEquals(listOf("</span><img src=x onerror=alert(1)>"), tex(source))
    }

    @Test
    fun `angle brackets in TeX are escaped rather than becoming tags`() {
        assertEquals(listOf("a < b > c"), tex("\$a < b > c\$"))
        assertFalse(html("\$<script>alert(1)</script>\$").contains("<script>"))
    }


    // --- Nothing else changed ---------------------------------------------------------------------

    @Test
    fun `ordinary Markdown is untouched by the extension`() {
        assertTrue(html("**bold** and _italic_").contains("<strong>bold</strong>"))
        assertTrue(html("| a | b |\n| - | - |\n| 1 | 2 |").contains("<table>"))
        assertTrue(html("~~gone~~").contains("<del>"))
        assertTrue(html("[link](https://example.org)").contains("rel=\"noopener noreferrer\""))
    }
}
