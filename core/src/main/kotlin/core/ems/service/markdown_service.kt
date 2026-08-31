package core.ems.service

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Service


/**
 * Renders author-written markdown to the HTML that gets stored in the `*_html` columns and injected
 * into the DOM by the web client, without going through React's escaping.
 *
 * The markdown is untrusted input, so the HTML this produces has to be constrained to a set of tags
 * and attributes that were chosen rather than inherited. Two things make that necessary rather than
 * belt-and-braces:
 *
 *  - commonmark's `HtmlRenderer` passes **raw HTML through verbatim** by default (`escapeHtml`
 *    defaults to false) and does not touch link targets (`sanitizeUrls` defaults to false). Anything
 *    an author types between the markdown ends up in the output as markup.
 *  - the client renders these columns with `dangerouslySetInnerHTML` and has no sanitiser of its
 *    own, so this function is the only place the question is asked.
 *
 * So the pipeline is parse → render → **clean against [SAFELIST]** → externalise links →
 * conceal [HIDDEN_TEXT_TAG].
 */
@Service
class MarkdownService {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        // `$x$` and `$$x$$`, typeset in the browser by KaTeX. Has to be a parser extension rather
        // than a client-side scan of this output: see MathExtension (EZ-1732).
        MathExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    // Read-only once built, so one instance is shared across requests.
    private val cleaner = Cleaner(SAFELIST)

    fun mdToHtml(content: String): String {
        val document = parser.parse(content)
        val html = renderer.render(document)
        return sanitise(html, cleaner)
    }
}

/**
 * The allowed markup.
 *
 * **This list is not a default from the library.** `Safelist.relaxed()` was the obvious starting
 * point and is wrong here in both directions: it drops `del` (which the strikethrough extension
 * emits), `hr` (which `---` emits) and `class` (which carries `language-*` to the highlighter), and
 * it knows nothing about the raw HTML that real content contains. The set below was chosen by
 * measuring what our own renderer emits for every markdown construct, and what raw HTML the existing
 * corpus actually uses — the collapsible `details`/`summary` hint blocks, the `div`/`class` wrappers
 * and `figure`s that the asciidoc-to-markdown conversion left behind, raw tables with
 * `colgroup`/`col`, and `sub`/`sup`. A tag missing from here does not fail; it silently stops
 * rendering, which is why the accompanying test renders one example of each and asserts it survives.
 *
 * **Adding a commonmark extension means adding its output here.** An extension emits markup this list
 * has never heard of — a maths extension emits `math` and friends, a footnote extension emits `section`
 * and `sup` wrappers — and the cleaner will remove all of it. The symptom is not an exception or a
 * failed build but a feature that renders as nothing, so the extension and the safelist have to be
 * changed in the same commit, with a case added to `markupSurvives`.
 *
 * Adding to it is a security decision, so the rules the list follows:
 *
 *  - **no tag that can execute or navigate**: no `script`, `style`, `iframe`, `object`, `embed`,
 *    `svg`, `math`, `base`, `meta`, `link`, `applet`.
 *  - **no tag that can collect input**: no `form`, `input`, `button`, `textarea`, `select`. An
 *    author-drawn login box on the application's own origin is indistinguishable from the real one.
 *  - **no event handlers**, which comes for free: jsoup keeps only the attributes named here, and
 *    `on*` is never named.
 *  - **no `style` attribute, except on `col`.** It cannot execute anything in a current browser, but
 *    it can position an element over the whole viewport, which is the same phishing surface `form` is
 *    excluded for. `col` is exempt because CSS ignores all but four properties there — see the note on
 *    that line. Everywhere else it goes, and content that has one loses its inline styling the next
 *    time somebody saves it. The corpus was counted before choosing that: `col` is where nearly all of
 *    them are, and what remains is a scattering on `span`, `td`, `th`, `img` and `p`. Keeping the
 *    exemption narrow costs little because of how lopsided that is.
 *  - **`title` is allowed** and is the one attribute whose value is author-controlled free text. It
 *    is escaped as text by jsoup's output, so it renders as a tooltip and nothing else.
 *
 * **`class` is allowed and `style` is not, and that pair needs its reason stated**, because a class
 * can reach the same phishing outcome — if the application's own stylesheet happens to position
 * whatever the author names. The difference is that `style` acts unconditionally on any page and a
 * `class` only does anything where the app already cooperates, so one is a capability and the other
 * is a collision. `class` is also not optional: the asciidoc-to-markdown conversion put block classes
 * throughout the stored corpus, and removing it would change how a large part of the library renders.
 * The collision is real though, and not only cosmetic — `PreviousSubmissions.tsx` locates the editor
 * with `document.querySelector('.cm-editor')`, which takes the first match in document order, and the
 * exercise text renders above it. Selectors that have to find one specific element should not be
 * matching on a class that content can also carry.
 *
 * **This only constrains HTML written from here on.** Rows already stored were rendered by earlier
 * code — and for the asciidoc era by a different renderer entirely, with no markdown source to
 * re-render from — so they keep whatever they hold. That is a deliberate decision rather than an
 * oversight: the stored corpus was checked for every construct this list removes before the decision
 * was made, and cleaning it in place would strip the very markup the asciidoc conversion depends on.
 */
private val SAFELIST: Safelist = Safelist()
    .addTags(
        "a", "abbr", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup", "dd",
        "del", "details", "div", "dl", "dt", HIDDEN_TEXT_TAG, "em", "figcaption", "figure", "h1",
        "h2", "h3", "h4",
        "h5", "h6", "hr", "i", "img", "ins", "kbd", "li", "mark", "ol", "p", "pre", "q", "s", "samp",
        "small", "span", "strong", "sub", "summary", "sup", "table", "tbody", "td", "tfoot", "th",
        "thead", "tr", "u", "ul", "var",
    )
    // These are on every tag because the converted corpus puts them on many, and none of them is a
    // value the client executes:
    //  - `class` — the asciidoc block classes, and `language-*` on fenced code. Nothing in `web/`
    //    reads `language-*` today; it is the conventional hook a highlighter would use, and it is
    //    already in the stored HTML, so stripping it would lose information for no gain.
    //  - `data-lang`, `data-wrapper` — likewise already in the corpus and likewise unread. Named
    //    rather than wildcarded because jsoup has no `data-*` wildcard and because a list is
    //    reviewable.
    //  - `id` — the target of an in-page `href="#…"`, which is why it cannot simply be dropped. It is
    //    author-controlled, so two blocks on one page can mint the same id and a fragment link will
    //    resolve to whichever came first. That is a content bug rather than a security one; a page
    //    that renders several of these blocks at once, such as the activity feed, is where it shows.
    //  - `data-easy-math`, `data-easy-tex` — the maths extension (EZ-1732) carries the authored TeX in
    //    an attribute and lets KaTeX typeset it in the browser. Named here even though that extension
    //    is on another branch, because the two changes break each other in the silent direction: the
    //    extension has no reason to know a safelist exists, so whichever lands second would ship
    //    formulas that render as empty boxes with nothing failing. The values are inert in the DOM —
    //    jsoup escapes them and only KaTeX reads them.
    .addAttributes(
        ":all", "class", "id", "title", "dir", "lang", "role",
        "data-lang", "data-wrapper", "data-easy-math", "data-easy-tex",
    )
    // Deliberately no `target` or `rel` here: [externaliseLinks] sets both after the clean, so
    // leaving them out makes "every anchor's target and rel come from us" true by construction
    // rather than by hoping the author did not write `target="_self" rel="opener"`.
    //
    // `name` is here for the asciidoc era, which anchored cross-references with `<a name="…">`
    // rather than `id`. Keeping `href="#…"` working while silently dropping half of what it points
    // at would have been the worse kind of partial fix.
    .addAttributes("a", "href", "name")
    .addAttributes("img", "src", "alt", "width", "height")
    // `type` gives a lettered or roman list, which is what asciidoc's `loweralpha` and friends
    // became. Without it such a list silently renumbers itself 1, 2, 3.
    .addAttributes("ol", "start", "type")
    .addAttributes("li", "value")
    .addAttributes("td", "colspan", "rowspan", "align", "valign")
    .addAttributes("th", "colspan", "rowspan", "align", "valign", "scope", "abbr")
    // `style` on `col` is the one exception to the rule below, and it is a narrow one. Table column
    // widths are written this way — `<col style="width: 33.3333%;">` is what a proportional table
    // becomes — and CSS applies only `border`, `background`, `width` and `visibility` to a table
    // column element (CSS 2.1 §17.3). So the thing `style` is otherwise excluded for, positioning an
    // element over the page, is not expressible here: the declaration is ignored. Without this,
    // every table with proportional columns loses them on its next save.
    .addAttributes("col", "span", "width", "style")
    .addAttributes("colgroup", "span")
    .addAttributes("table", "align")
    .addAttributes("details", "open")
    .addAttributes("blockquote", "cite")
    .addAttributes("q", "cite")
    // The protocol allowlists are what stop `javascript:` and `data:text/html` in a link. `data:`
    // is allowed on `img[src]` only, where a few exercises inline a small image and where a data URL
    // cannot become a scripting context.
    .addProtocols("a", "href", "http", "https", "mailto")
    .addProtocols("img", "src", "http", "https", "data")
    .addProtocols("blockquote", "cite", "http", "https")
    .addProtocols("q", "cite", "http", "https")
    // See [SANITISER_BASE_URI] — without this every relative link loses its href entirely.
    .preserveRelativeLinks(true)

/**
 * Only ever used to resolve relative URLs while checking them, never emitted.
 *
 * jsoup enforces the protocol allowlists against the **absolutised** URL, and with no base URI a
 * relative one absolutises to the empty string, matches no protocol and has its `href` or `src`
 * removed. That silently breaks every `/v2/resource/...` image and every in-app link — the failure
 * this constant exists to prevent, and one that `preserveRelativeLinks(true)` alone does *not*
 * prevent, because that flag governs what is written back, not what is checked.
 *
 * With a base URI the check resolves against it and passes, and because `preserveRelativeLinks` is
 * on, jsoup writes the author's original relative value back out. A reserved `.invalid` name makes
 * it obvious that the value is a placeholder and cannot accidentally become a real request if some
 * future change starts emitting it.
 */
private const val SANITISER_BASE_URI = "https://easy.invalid/"

/**
 * The tag an author writes to hide text, and what it is given after the clean. Named constants so
 * the safelist entry, the pass that styles it and the tests all agree on one spelling.
 */
private const val HIDDEN_TEXT_TAG = "easy-hidden"

/**
 * **Containment, not inheritance, and that distinction is the whole of this constant.** The first
 * version of this was `font-size:0;color:transparent`, which hides only descendants that inherit
 * both — and `proseStyles.ts` gives `h4`–`h6` their own `fontSize` and `color`, tables and
 * `blockquote` their own borders, and `img` a size that no font-size can reach. A canary containing
 * a heading therefore rendered at full size in front of the class, which is precisely the failure
 * this whole mechanism exists to prevent. Clipping the element contains whatever it holds, however
 * that content is styled.
 *
 * Still not `display:none` or `visibility:hidden`: the text has to travel with a copy of the
 * description, and neither of those is copied. A clipped element is still laid out and still
 * selected, which is what keeps the mechanism working.
 */
private const val HIDDEN_TEXT_STYLE =
    "position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap"

private fun sanitise(html: String, cleaner: Cleaner): String {
    // `parseBodyFragment` and not `parse`: `parse` builds a whole document and moves a leading
    // `<script>` into `<head>`, which then falls outside `body().html()`. That makes the simplest
    // possible test payload appear to be handled by a step that is not handling anything, and any
    // payload with text in front of it behave differently from the same payload alone. Parsing in
    // body context means what the cleaner sees is what an author wrote, in the order they wrote it.
    val dirty = Jsoup.parseBodyFragment(html, SANITISER_BASE_URI)
    val clean = cleaner.clean(dirty)
    clean.outputSettings(dirty.outputSettings())
    externaliseLinks(clean)
    concealHiddenText(clean)
    return clean.body().html()
}

private fun externaliseLinks(jdoc: org.jsoup.nodes.Document) {
    jdoc.getElementsByTag("a").forEach {
        it.attr("target", "_blank")
            .attr("rel", "noopener noreferrer")
    }
}

/**
 * `<easy-hidden>` is text meant to be in the page but not on it — the anti-LLM canaries some
 * exercises carry, which a student pasting the description into a chatbot takes with them.
 *
 * **The styling is written here rather than in `web/`, and that is the whole point.** A CSS rule
 * fails open: one stylesheet that does not load, or one consumer of `text_html` that is not the
 * exercise page, and the canary is on screen in front of the class. Baking the declaration into the
 * stored HTML means the text is hidden wherever that HTML is rendered. The cost is the EZ-1792 one —
 * changing the styling later means re-rendering the corpus — which is the right trade for a
 * declaration that should never change.
 *
 * **`font-size: 0; color: transparent` and deliberately not `display: none`.** The mechanism depends
 * on the text being carried along by a copy of the description, and `display: none` is not copied.
 * The exercises this replaces used `opacity: 0`, `color: white` and `font-size: 0.000001em` for the
 * same reason, before the safelist stopped honouring `style` from an author.
 *
 * `aria-hidden` because the alternative is a screen reader announcing the canary to the one student
 * who cannot see that it is hidden — which is both a worse experience and a worse trap.
 *
 * Set here, after the clean, for the same reason as [externaliseLinks]: `style` is not safelisted on
 * this tag, so the declaration is ours by construction rather than by trusting the input. The
 * author's `class`, `id`, `title`, `role`, `dir` and `lang` do survive, as they do on every tag —
 * inert here, because an inline declaration beats any stylesheet rule a class could name.
 *
 * The `tabindex` is not decoration. `aria-hidden` on a subtree containing a focusable element is an
 * accessibility fault in its own right (axe's `aria-hidden-focus`): a keyboard user would land on a
 * link inside a clipped canary that the screen reader is instructed not to announce. Markdown can
 * put a link in there, so the pass takes it out of the tab order.
 */
private fun concealHiddenText(jdoc: org.jsoup.nodes.Document) {
    jdoc.getElementsByTag(HIDDEN_TEXT_TAG).forEach { hidden ->
        hidden.attr("style", HIDDEN_TEXT_STYLE)
            .attr("aria-hidden", "true")
        hidden.select("a[href]").forEach { it.attr("tabindex", "-1") }
    }
}
