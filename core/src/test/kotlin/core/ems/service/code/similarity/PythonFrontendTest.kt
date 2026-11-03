package core.ems.service.code.similarity

import de.jplag.python3.grammar.Python3Lexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.dfa.DFA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import kotlin.random.Random

@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PythonFrontendTest {

    private fun types(source: String, cap: Int = 10_000) = PythonFrontend.read(File("a"), source, cap).tokens.map { it.type }

    /** On a stack as deep as the workers', where the parser runs. */
    private fun <T> onDeepStack(block: () -> T): T {
        val task = FutureTask(block)
        Thread(null, task, "deep-stack", 64L shl 20).apply { start() }.join()
        return try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `a copy with every name changed, comments added, lines spread out and form feeds inserted reads as the same tokens`() {
        val program = "def f(xs):\n    total = 0\n    for x in xs:\n        if x > 0:\n            total = total + x\n    return total\n"
        val disguised = "# my own work\n\ndef summa(arvud):\n\n    s = 0  # start\n    for\u000C a in arvud:\n" +
            "        if a > 0:\n            s =\u000C s + a\n\n  \u000C    return s\n"
        assertEquals(types(program), types(disguised))
    }

    @Test
    fun `comments do not count toward the token cap, so padding cannot take a copy out of the comparison`() {
        val code = "x = 1\ny = 2\n"
        assertEquals(types(code, cap = 20), types(code + "# " + "padding ".repeat(10_000) + "\n", cap = 20))
        assertThrows<SolutionTooLarge> { types(code + "z = 3\n".repeat(10), cap = 20) }
    }

    /** Each input is stopped by its own limit, named in the refusal: each limit is there for one of them. */
    @Test
    fun `input that would hold the parser for minutes is refused as too complex within seconds, each by its own limit`() {
        val random = Random(7)
        val costly = listOf(
            // Deeper than CPython allows, which the parser took minutes over.
            Triple("brackets", "x = " + "(".repeat(250) + "1" + ")".repeat(250) + "\n", "Brackets nest"),
            // Full LL prediction on nested minus signs is superlinear; the broken line forces LL.
            Triple("prediction", "x = " + "-".repeat(1_000) + "1\nthis is not python (((\n", "Parsing took more than"),
            // The listener rebuilds text under each node, and the walk took half a minute.
            Triple("walk", "x = " + "f(".repeat(199) + "\"" + "a".repeat(60_000) + "\"" + ")".repeat(199) + "\n", "Walking the parse"),
            // Mixed brackets grow the shared prediction cache by a state per distinct stack of rules, and
            // what one parse added stayed in it - hundreds of MB - after the parse was refused.
            Triple("cache", (0 until 30).joinToString("") { line ->
                val kinds = List(150) { random.nextInt(3) }
                "v$line = " + kinds.joinToString("") { "([{"[it].toString() } + "x" + kinds.reversed().joinToString("") { ")]}"[it].toString() } + "\n"
            }, "prediction cache"),
        )
        for ((name, source, limit) in costly) {
            val started = System.nanoTime()
            val refused = assertThrows<SolutionTooComplex>(name) { onDeepStack { types(source) } }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(limit in refused.message.orEmpty(), "$name refused by another limit: ${refused.message}")
            assertTrue(tookMs < 10_000, "$name took ${tookMs}ms")
        }
    }

    /** Kept for good, what pathological parses added to the shared cache held hundreds of MB until a restart. */
    @Test
    fun `a prediction cache past its bound is replaced by an empty one`() {
        val full = PythonParserCache.current
        full.states.set(PythonParserCache.MAX_STATES + 1)
        PythonParserCache.retireIfFull(full)
        assertNotSame(full, PythonParserCache.current)
        assertEquals(0, PythonParserCache.current.states.get())
    }

    /**
     * The start-state cache overrides ANTLR internals, so an upgrade could change which tokens it gives
     * without anything else failing: held to the stock lexer's tokens over inputs that open in every way the
     * cached predicate looks at, one cache across all of them.
     */
    @Test
    fun `the caching lexer gives exactly the stock lexer's tokens`() {
        fun lex(source: String, cache: Array<DFA>?, contexts: PredictionContextCache?): List<String> {
            val lexer = Python3Lexer(CharStreams.fromString(source))
            lexer.removeErrorListeners()
            if (cache != null) lexer.interpreter = StartStateCachingLexerATNSimulator(lexer, lexer.atn, cache, contexts!!)
            val stream = CommonTokenStream(lexer)
            stream.fill()
            return stream.tokens.map { "${it.type}@${it.line}:${it.charPositionInLine}/${it.channel} ${it.startIndex}-${it.stopIndex}" }
        }
        val random = Random(1875)
        val statements = listOf("x = 1", "total += x", "print(x, 'y')", "s = \"\"\"a\nb\"\"\"", "value = (a +\n    b)", "# c", "", "   ", "if x: y = 2", "this is not python (((", "\tz = 3")
        val openings = listOf("", "   ", "\t", "\n", "\n\n  ", "  \n", "# c\n", "\u000c", "\r\n  ")
        val inputs = List(60) { index ->
            openings[index % openings.size] + buildString {
                repeat(random.nextInt(1, 10)) { append("    ".repeat(random.nextInt(0, 3))).append(statements.random(random)).append('\n') }
            }
        }
        val cache = Array(Python3Lexer._ATN.numberOfDecisions) { DFA(Python3Lexer._ATN.getDecisionState(it), it) }
        val contexts = PredictionContextCache()
        for (input in inputs) assertEquals(lex(input, null, null), lex(input, cache, contexts), input.take(60))
    }
}
