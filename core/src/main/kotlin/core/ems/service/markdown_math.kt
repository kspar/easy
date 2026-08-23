package core.ems.service

import org.commonmark.node.CustomBlock
import org.commonmark.node.CustomNode
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.parser.SourceLine
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

/**
 * LaTeX maths in Markdown: `$x^2$` inline, `$$…$$` displayed (EZ-1732).
 *
 * ## Why this is a parser extension and not a client-side scan
 *
 * The tempting version of this feature is a client-side scan of the rendered HTML for `$…$` — it is
 * what the old WUI did with MathJax, and it needs no backend change at all. It is also wrong,
 * because by the time the HTML exists the maths has already been through a Markdown parser that had
 * no idea it was maths:
 *
 * - `$a * b * c$` comes out as `a <em> b </em> c` — the asterisks are gone, and with them the
 *   multiplication.
 * - `$x_1 + x_2$` is safe only by accident: CommonMark disables intraword `_` emphasis. Put a space
 *   anywhere near it and it is not.
 * - `$\\alpha$` — a TeX line break followed by a macro — becomes `$\alpha$`, because a backslash
 *   escapes a backslash in Markdown. The adoc→md migration hit exactly this and needed a
 *   "dangling-backslash fix"; see `doc/core/adoc-to-md/README.md`.
 *
 * None of that is recoverable downstream. So maths is claimed *during* parsing, before emphasis and
 * escape processing get a chance, and the TeX reaches the renderer byte-for-byte as authored.
 *
 * ## What it emits, and why the TeX is in an attribute
 *
 * ```html
 * <span class="easy-math" data-easy-math="inline" data-easy-tex="x^2">$x^2$</span>
 * <div class="easy-math" data-easy-math="display" data-easy-tex="x^2">$$x^2$$</div>
 * ```
 *
 * Typesetting stays in the browser (KaTeX, `web/src/components/markdown/renderMath.ts`). There is no
 * credible TeX→MathML library for the JVM, and the client already has to run for the 48 exercises
 * whose stored `text_html` predates this file.
 *
 * `data-easy-tex` is the authority rather than the element text, because [MarkdownService] passes
 * the output through Jsoup, whose pretty-printer normalises whitespace inside text nodes and would
 * be free to reflow a `pmatrix`. Attribute values it never touches. The element text is the original
 * source *with* its delimiters, so the failure mode when KaTeX does not load is what students see
 * today — `$x^2$` — rather than a blank space where a formula was.
 *
 * ## Delimiters: `$` only
 *
 * Not `\(…\)`, which is what Asciidoctor used to emit and the obvious thing to keep supporting. It
 * cannot work: `(` is ASCII punctuation, so Markdown turns `\(x\)` into the literal text `(x)`
 * before anything here could look at it. The adoc→md migration already rewrote those 48 exercises to
 * `$…$` for this reason.
 *
 * ## The known gap in the currency heuristic
 *
 * `$…$` is ambiguous in prose, and the rules below — no whitespace inside the delimiters, no digit
 * after the closer — resolve the cases that actually occur: `$5 and $6`, `$5-$10`, `$PATH ja $HOME`.
 * What they do not resolve is two shell variables joined by punctuation, `$PATH:$HOME`, which
 * becomes a formula reading `PATH:`. Accepted rather than fixed: any rule strong enough to catch it
 * (a macro-name allowlist, an uppercase-only veto) also rejects real one-symbol maths like `$T$`,
 * and on a programming course `$T$` is likelier than an un-code-spanned `$PATH:$HOME`. Backticks
 * are the escape hatch and are what such a line wants anyway; `\$` works too.
 */
class MathExtension private constructor() : Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {

    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customBlockParserFactory(MathBlockParserFactory())
        parserBuilder.customInlineContentParserFactory(MathInlineParserFactory())
    }

    override fun extend(rendererBuilder: HtmlRenderer.Builder) {
        rendererBuilder.nodeRendererFactory { MathNodeRenderer(it) }
    }

    companion object {
        fun create(): MathExtension = MathExtension()
    }
}

/** Inline `$x$`, and `$$x$$` written mid-sentence. [display] picks KaTeX's rendering mode. */
class MathInline(val tex: String, val display: Boolean) : CustomNode()

/**
 * A `$$` fence, or a whole line of `$$x$$`. Always displayed.
 *
 * [tex] is mutable because a fenced block's content is only known at its closing fence, and by then
 * the node is already in the tree — CommonMark takes `getBlock()` when the parser starts, so a node
 * built at close time would be a second one that nothing points at.
 */
class MathBlock(var tex: String = "") : CustomBlock()

private const val CLASS = "easy-math"

/**
 * Takes the backticks off `` $`x^2`$ ``.
 *
 * **This is the form the corpus is actually written in.** The adoc→md migration emitted dollars
 * wrapping a code span, and 43 of the 48 exercises carrying maths look like this — including
 * exercise 164, whose formula was reported as not rendering and is what led here. Only 5 use the
 * plain `$…$` this extension was first written against.
 *
 * Claiming the delimiters and passing the backticks through as TeX would be worse than not claiming
 * them: a backtick is not valid TeX, so those exercises would go from showing raw dollars to showing
 * a KaTeX error. Nothing downstream can undo it either — by then the backticks are indistinguishable
 * from ones an author typed.
 *
 * Three rules, and each one is a test:
 *
 * - the run has to **wrap the whole formula**, so a lone backtick mid-formula stays where the author
 *   put it;
 * - the runs have to **match in length**, because that is what makes it a code span rather than
 *   stray punctuation — and stripping half of an unmatched pair would corrupt TeX that legitimately
 *   contains a backtick;
 * - there has to be **something left over**, since unwrapping to nothing is the empty-formula case
 *   that made a whole line disappear once already.
 *
 * Done here rather than in the code-span parser because CommonMark never sees a code span at all:
 * [MathInlineParser] claims the whole `$…$` span first, so the backticks arrive as ordinary
 * characters inside the TeX and this is the only place they can be recognised for what they are.
 */
internal fun unwrapCodeSpan(tex: String): String {
    val leading = tex.takeWhile { it == '`' }.length
    if (leading == 0) return tex

    val trailing = tex.takeLastWhile { it == '`' }.length
    // `tex.length > leading * 2` rather than `>=`: equal means the whole string is backticks, so
    // there is no formula to unwrap to.
    if (trailing != leading || tex.length <= leading * 2) return tex

    return tex.substring(leading, tex.length - trailing)
}

private class MathNodeRenderer(private val context: HtmlNodeRendererContext) : NodeRenderer {

    override fun getNodeTypes(): Set<Class<out Node>> = setOf(MathInline::class.java, MathBlock::class.java)

    override fun render(node: Node) {
        val writer = context.writer
        when (node) {
            is MathInline -> {
                val delim = if (node.display) "$$" else "$"
                writer.tag("span", attrs(node, "span", if (node.display) "display" else "inline", node.tex))
                writer.text("$delim${node.tex}$delim")
                writer.tag("/span")
            }

            is MathBlock -> {
                // An unterminated bare `$$` at the end of a document leaves nothing to typeset.
                // Emitting the element anyway gives KaTeX an empty formula, which it renders as a
                // stray empty box in the middle of the text.
                if (node.tex.isBlank()) return
                writer.line()
                writer.tag("div", attrs(node, "div", "display", node.tex))
                writer.text("$$${node.tex}$$")
                writer.tag("/div")
                writer.line()
            }
        }
    }

    /**
     * Through `extendAttributes` rather than built as a literal map, so an AttributeProvider can
     * still see these elements — that is the contract every core renderer honours, and a node type
     * that quietly opted out of it would be a surprise to whoever adds the first provider.
     */
    private fun attrs(node: Node, tag: String, mode: String, tex: String): Map<String, String> =
        context.extendAttributes(
            node, tag, linkedMapOf("class" to CLASS, "data-easy-math" to mode, "data-easy-tex" to tex)
        )
}


// --- Inline: $x$ and $$x$$ ------------------------------------------------------------------------

private class MathInlineParserFactory : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = setOf('$')
    override fun create(): InlineContentParser = MathInlineParser()
}

private class MathInlineParser : InlineContentParser {

    /**
     * Nullable, because CommonMark's `none()` factories *are* null — `ParsedInline.none()` and
     * `BlockStart.none()` both `return null`. A non-null return type here compiles and then throws
     * `NullPointerException: none(...) must not be null` the first time a dollar sign is not maths,
     * which is most of them.
     */
    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()

        // A dollar immediately before this one means we are standing in the middle of a longer run
        // and the run has already been declined once. Without this, `$$$x$$$` still becomes maths:
        // the first attempt refuses three dollars, CommonMark emits one as text and re-enters us one
        // character later, where the remaining `$$x$$` looks perfectly well-formed.
        if (scanner.peekPreviousCodePoint() == '$'.code) return ParsedInline.none()

        // Two at most. `$$$` is not a delimiter in any dialect, and claiming it would turn a row of
        // dollars in prose into a formula. The caller restores the position when we decline.
        val open = scanner.matchMultiple('$')
        if (open > 2) return ParsedInline.none()

        // No whitespace directly after the opening delimiter: this is the rule that keeps "$5 and
        // $6" out of the maths parser, and it is why `$ x $` has to be written `$x$`.
        val first = scanner.peek()
        if (first == Scanner.END || first.isWhitespace()) return ParsedInline.none()

        val tex = readTex(scanner, open) ?: return ParsedInline.none()

        // A digit straight after the closing `$` means we almost certainly ate a currency amount:
        // "$5 and $6" opens at the first `$`, finds a closer before the 6, and would otherwise
        // typeset "5 and ". Only for single `$` — `$$…$$` is unambiguous enough not to need it.
        if (open == 1 && scanner.peek().isDigit()) return ParsedInline.none()

        return ParsedInline.of(
            MathInline(unwrapCodeSpan(tex), display = open == 2),
            scanner.position(),
        )
    }

    /**
     * Reads up to the closing run of exactly [open] dollars, leaving the scanner past it.
     *
     * Null means there is no closer, which makes the dollar we consumed ordinary text again — the
     * only acceptable outcome, since a `$` in prose must survive being a `$` in prose.
     */
    private fun readTex(scanner: Scanner, open: Int): String? {
        val tex = StringBuilder()
        var escaped = false
        while (true) {
            val c = scanner.peek()
            if (c == Scanner.END) return null

            if (c == '$' && !escaped) {
                val before = scanner.position()
                val run = scanner.matchMultiple('$')
                if (run == open) {
                    // No whitespace directly before the closer, mirroring the opening rule, and
                    // nothing at all is not a formula.
                    val t = tex.toString()
                    return if (t.isBlank() || t.last().isWhitespace()) null else t
                }
                // A run of the wrong length is content: a lone `$` inside `$$…$$`, or `$$` inside
                // `$…$`. Take one character and re-examine the rest, so `$a$$b$` still closes.
                scanner.setPosition(before)
                tex.append('$')
                scanner.next()
                escaped = false
                continue
            }

            // `\$` is a dollar in TeX too, so a backslash suppresses the delimiter rather than
            // being stripped. Note `\\` cancels itself: in `$\\$` the closer still closes.
            escaped = c == '\\' && !escaped
            tex.append(c)
            scanner.next()
        }
    }
}


// --- Block: a $$ fence, or one line of $$x$$ ------------------------------------------------------

private const val FENCE = "$$"

private class MathBlockParserFactory : AbstractBlockParserFactory() {

    /** Nullable for the same reason as [MathInlineParser.tryParse]: `BlockStart.none()` is null. */
    override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
        // Four spaces is an indented code block, and a formula shown as source is a legitimate
        // thing to want.
        if (state.indent >= 4) return BlockStart.none()

        val line = state.line.content.toString()
        val content = line.substring(state.nextNonSpaceIndex).trimEnd()
        if (!content.startsWith(FENCE)) return BlockStart.none()

        // Deliberately only the two exact shapes: a bare `$$`, or a complete one-liner. Anything
        // else starting with `$$` falls through to a paragraph, where the inline parser gets a
        // look. Being this strict is what makes it safe to interrupt an open paragraph the way a
        // fenced code block does — and interrupting matters, because "Consider:" on the line above
        // a formula is how people actually write.
        if (content == FENCE) return BlockStart.of(MathBlockParser(null)).atIndex(line.length)

        if (content.length <= 2 * FENCE.length || !content.endsWith(FENCE)) return BlockStart.none()
        val middle = content.substring(FENCE.length, content.length - FENCE.length)
        // A second `$$` in the middle means this is not one formula but two written side by side —
        // `$$a$$ and $$b$$`. Hand it to the paragraph, whose inline parser gets both right.
        if (middle.contains(FENCE)) return BlockStart.none()
        // A dollar either side of the middle means the delimiter run was longer than two: `$$$x$$$`
        // is a row of dollars, not a formula, and the inline parser declines it for the same reason.
        if (middle.startsWith("$") || middle.endsWith("$")) return BlockStart.none()
        // `$$ $$` has a middle, passes every guard above, and holds no TeX — so it used to produce
        // a block the renderer then dropped, and the line the author typed disappeared from the
        // page. Declining here leaves it as the literal text it looks like.
        if (middle.isBlank()) return BlockStart.none()
        return BlockStart.of(MathBlockParser(middle.trim())).atIndex(line.length)
    }
}

private class MathBlockParser(private val oneLiner: String?) : AbstractBlockParser() {

    private val lines = mutableListOf<String>()
    private val block = MathBlock(oneLiner ?: "")

    override fun getBlock(): MathBlock = block

    /**
     * Nullable, and this is the third place in this file where that matters — `BlockContinue.none()`
     * `return null`s just like the other two `none()` factories. Declaring it non-null compiled
     * fine and then threw `NullPointerException: none(...) must not be null` on the *first line
     * after* a one-line `$$…$$`, because a one-liner alone in a document is finalised without
     * `tryContinue` ever being called. The blast radius was the whole content pipeline:
     * `mdToHtml` throwing means `/preview/markdown` and every save that derives HTML — exercise,
     * article, feedback, inline comment — answer 500.
     */
    override fun tryContinue(state: ParserState): BlockContinue? {
        if (oneLiner != null) return BlockContinue.none()
        // A blank line ends the fence. It is illegal in TeX math mode anyway, so nothing real is
        // lost, and it bounds the damage of a missing closing `$$` to the formula instead of
        // swallowing every paragraph and heading below it into one unreadable red block.
        if (state.isBlank) return BlockContinue.none()
        val content = state.line.content.toString().substring(state.nextNonSpaceIndex).trimEnd()
        // The closing fence is consumed by finishing here; `finished()` does not hand it on.
        return if (state.indent < 4 && content == FENCE) BlockContinue.finished()
        else BlockContinue.atIndex(state.index)
    }

    override fun addLine(line: SourceLine) {
        lines += line.content.toString()
    }

    /**
     * An unterminated fence closes at the blank line or the end of its container rather than being
     * abandoned, which is how fenced code blocks behave. The alternative — dropping the block —
     * would silently eat a teacher's formula over a missing `$$`.
     */
    override fun closeBlock() {
        // Unwrapped here as well as inline: the migration's `$`…`$` form appears in display maths
        // too, and a fenced block whose body is one code span is the same mistake at a larger size.
        block.tex =
            if (oneLiner == null) unwrapCodeSpan(lines.joinToString("\n").trim())
            else unwrapCodeSpan(oneLiner)
    }
}
