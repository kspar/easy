package core.ems.service.code.similarity

import de.jplag.python3.grammar.Python3Lexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.dfa.DFA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.random.Random

/**
 * The Python frontend's performance changes, held to the output they must not change.
 *
 * All of them are invisible when wrong. A lexer start state cached at the wrong moment lexes a first
 * line's indentation differently, and nothing fails: the tokens are merely different, and so are the
 * scores built on them. So the caching lexer is compared with the stock one token for token, on a
 * cache it shares across inputs, and in an order that gives each way of getting it wrong a chance to
 * show - a file that starts indented straight after one that cached the state, and the reverse.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PythonFrontendTest {

    private fun freshDfa(): Array<DFA> =
        Array(Python3Lexer._ATN.numberOfDecisions) { DFA(Python3Lexer._ATN.getDecisionState(it), it) }

    /** Every token as the parser would be handed it; `cache` null is the stock lexer, untouched. */
    private fun lex(
        source: String,
        cache: Array<DFA>?,
        contexts: PredictionContextCache? = null,
        falsePastStart: ((Int, Int) -> Boolean)? = null,
    ): List<String> {
        val lexer = Python3Lexer(CharStreams.fromString(source))
        lexer.removeErrorListeners()
        if (cache != null) {
            lexer.interpreter = if (falsePastStart == null) {
                StartStateCachingLexerATNSimulator(lexer, lexer.atn, cache, contexts!!)
            } else {
                StartStateCachingLexerATNSimulator(lexer, lexer.atn, cache, contexts!!, falsePastStart)
            }
        }
        val stream = CommonTokenStream(lexer)
        stream.fill()
        // The lexer's synthetic end-of-input tokens can carry a character range getText cannot read.
        return stream.tokens.map { t ->
            "${t.type}@${t.line}:${t.charPositionInLine}/${t.channel} ${t.startIndex}-${t.stopIndex} " +
                    runCatching { t.text }.getOrDefault("<no text>")
        }
    }

    /** Small programs with the shapes the grammar treats specially: blocks, brackets, strings, errors. */
    private fun programs(): List<String> {
        val random = Random(1875)
        val statements = listOf(
            "x = 1", "total += x", "print(x, 'y')", "items.append([1, 2,\n    3])", "s = \"\"\"a\nb\"\"\"",
            "value = (a +\n         b)", "c = 1 + \\\n    2", "# a comment", "", "   ", "d = {'k': [1, (2, 3)]}",
            "if x: y = 2", "return x if x else -y", "this is not python (((", "f = lambda: 0", "\tz = 3",
        )
        return List(60) {
            buildString {
                repeat(random.nextInt(1, 12)) {
                    val depth = random.nextInt(0, 3)
                    if (random.nextInt(4) == 0) append("    ".repeat(depth)).append("for i in range(3):\n")
                    append("    ".repeat(depth + random.nextInt(0, 2))).append(statements.random(random)).append('\n')
                }
            }
        }
    }

    /**
     * What happens at the start of input is the whole question, so every program is also tried behind
     * each kind of opening the NEWLINE rule's predicate looks at.
     */
    private fun inputs(): List<String> {
        val openings = listOf("", "   ", "\t", "\n", "\n\n  ", "  \n", "# c\n", " # c\n", "\u000c", "\r\n  ")
        val programs = programs()
        return programs.flatMapIndexed { index, program ->
            listOf(openings[index % openings.size] + program, program, openings[(index + 3) % openings.size] + program)
        } + listOf("", " ", "\n", "x = 1", "x = 1\r\n", "    x = 1\n", "\tif x:\n\t\ty\n", "\r", "  \r\n", "if a:\n    b\n  c\n")
    }

    @Test
    fun `the start-state-caching lexer produces exactly the stock lexer's tokens`() {
        // One cache for the whole list, so later inputs are lexed on a start state earlier ones cached;
        // then the reverse order on another, so the first input to cache it is a different one.
        for (ordered in listOf(inputs(), inputs().reversed())) {
            val cache = freshDfa()
            val contexts = PredictionContextCache()
            ordered.forEachIndexed { index, input ->
                assertEquals(
                    lex(input, cache = null), lex(input, cache, contexts),
                    "input $index lexed differently with the start state cached: ${input.take(60).replace("\n", "\\n")}",
                )
            }
        }
    }

    @Test
    fun `a start state whose closure went through a predicate it does not know is not cached`() {
        // The cache is sound because of one predicate's behaviour, so a closure that met any other
        // - a grammar that gained one - must fall back to what ANTLR does and cache nothing. Trusting
        // no predicate at all reaches that path on today's grammar: the tokens must still be the stock
        // lexer's, and the cache must stay empty.
        val cache = freshDfa()
        val contexts = PredictionContextCache()
        inputs().take(40).forEach { input ->
            assertEquals(lex(input, cache = null), lex(input, cache, contexts, falsePastStart = { _, _ -> false }))
        }
        assertNull(cache[Lexer.DEFAULT_MODE].s0, "a start state was cached although its predicate was not trusted")
    }

    @Test
    fun `the SLL-first parse produces exactly the tokens LL alone does`() {
        // ANTLR promises that a successful SLL parse is the tree LL would build, and the fallback is
        // LL itself; this holds the implementation to the promise. Broken inputs are included, since
        // those are the ones that take the fallback. (An SLL stage left to recover instead of bail gave
        // the same tokens on all of these; what catches that is the TOO_COMPLEX test in
        // SimilarityServiceTest, since such a stage never hands a broken file to the budgeted LL.)
        fun signature(tokens: List<de.jplag.Token>) = tokens.map {
            "${it.type.description}@${it.startLine}:${it.startColumn}-${it.endLine}:${it.endColumn}"
        }
        inputs().forEachIndexed { index, input ->
            assertEquals(
                signature(PythonFrontend.tokeniseWithLlOnly(File("ll"), input)),
                signature(SimilarityLanguage.PYTHON.tokenise(File("ll"), input, Int.MAX_VALUE)),
                "input $index tokenised differently SLL-first: ${input.take(60).replace("\n", "\\n")}",
            )
        }
    }

    @Test
    fun `tokenising puts the start state in the cache, which is what makes it fast`() {
        // The comparison above cannot see the cache not being used at all: the stock simulator gives
        // the same tokens, only about a hundred times slower per token. This can.
        SimilarityLanguage.PYTHON.tokenise(File("cached"), "x = 1\ny = 2\n", 1_000)
        assertTrue(PythonLexerCache.startStateCached, "the lexer is not on the caching simulator, or it does not cache")
    }

    @Test
    fun `comments and blank lines do not count toward the token cap`() {
        val code = "x = 1\ny = 2\n"
        val padded = code + "# " + "padding ".repeat(10_000) + "\n\n\n" + "   # more\n".repeat(1_000)
        val cap = 20
        // The same tokens with or without the padding, and under a cap the padding is thousands above.
        assertEquals(
            SimilarityLanguage.PYTHON.tokenise(File("a"), code, cap).map { it.type },
            SimilarityLanguage.PYTHON.tokenise(File("a"), padded, cap).map { it.type },
        )
        assertThrows<SolutionTooLarge> {
            SimilarityLanguage.PYTHON.tokenise(File("a"), code + "z = 3\n".repeat(10), cap)
        }
    }

    @Test
    fun `the cap counts lexical tokens, not the line ends and indentation the grammar makes of them`() {
        // `x = 1` is three lexical tokens. Counted from the parser's stream, NEWLINE and EOF made it five,
        // so Python's cap was in another unit from every other frontend's.
        SimilarityLanguage.PYTHON.tokenise(File("a"), "x = 1\n", 3)
        assertThrows<SolutionTooLarge> { SimilarityLanguage.PYTHON.tokenise(File("a"), "x = 1\n", 2) }
        // `if x : y = 2 z = 3` - nine, whatever INDENT, DEDENT and NEWLINE the block adds.
        val block = "if x:\n    y = 2\n    z = 3\n"
        SimilarityLanguage.PYTHON.tokenise(File("a"), block, 9)
        assertThrows<SolutionTooLarge> { SimilarityLanguage.PYTHON.tokenise(File("a"), block, 8) }
    }

    @Test
    fun `a form feed is the whitespace it is to Python`() {
        // JPlag's grammar ends a line at a form feed, where CPython reads one inside a line as a space and
        // one in a line's indentation as the start of it: a verbatim copy with a few inserted scored 20%.
        val program = "def f(xs):\n    total = 0\n    for x in xs:\n        if x > 0:\n            total = total + x\n    return total\n"
        val fed = program.replace(" = ", " =\u000C ").replace("for ", "for\u000C ").replace("\n    return", "\n  \u000C    return")
        assertEquals(
            SimilarityLanguage.PYTHON.tokenise(File("a"), program, 1_000).map { it.type },
            SimilarityLanguage.PYTHON.tokenise(File("b"), fed, 1_000).map { it.type },
        )
    }

    @Test
    fun `JPlag's walk over the tree is held to a time limit too`() {
        // After the parse, JPlag's listener decides some tokens by getText(), which rebuilds the text under
        // a node at every level of it: 199 nested calls around one long string passed every cap, parsed in
        // a fifth of a second and then walked for half a minute, outside the parse's clock.
        val source = "x = " + "f(".repeat(199) + "\"" + "a".repeat(60_000) + "\"" + ")".repeat(199) + "\n"
        val started = System.nanoTime()
        assertThrows<SolutionTooComplex> { onDeepStack { SimilarityLanguage.PYTHON.tokenise(File("walk"), source, 10_000) } }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 10_000, "the walk ran for ${tookMs}ms")
    }

    @Test
    fun `no one parse can grow the shared prediction cache without bound, and a full cache is replaced`() {
        // Prediction on deeply mixed brackets adds a DFA state for each distinct stack of rules it passes
        // through, and the states outlived the parse that was refused for them: each such submission -
        // valid Python, under CPython's nesting limit and the token cap - kept ~120 MB for good.
        val random = Random(7)
        val deep = (0 until 30).joinToString("") { line ->
            val kinds = List(150) { random.nextInt(3) }
            "v$line = " + kinds.joinToString("") { "([{"[it].toString() } + "x" +
                kinds.reversed().joinToString("") { ")]}"[it].toString() } + "\n"
        }
        val refused = assertThrows<SolutionTooComplex> { onDeepStack { SimilarityLanguage.PYTHON.tokenise(File("deep"), deep, 10_000) } }
        assertTrue("prediction cache" in refused.message.orEmpty(), "refused by another bound: ${refused.message}")
        assertTrue(PythonParserCache.current.states.get() <= PythonParserCache.MAX_STATES + MAX_NEW_DFA_STATES)

        val full = PythonParserCache.current
        full.states.addAndGet(PythonParserCache.MAX_STATES + 1)
        PythonParserCache.retireIfFull(full)
        assertNotSame(full, PythonParserCache.current, "a cache past its bound was kept")
    }

    private fun <T> onDeepStack(block: () -> T): T {
        val task = java.util.concurrent.FutureTask(block)
        Thread(null, task, "deep-stack", 64L shl 20).apply { start() }.join()
        return try {
            task.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }
}
