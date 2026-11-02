package core.ems.service.code.similarity

import de.jplag.antlr.InMemoryTokenCollectorAccess
import de.jplag.python3.PythonParserAdapter
import de.jplag.python3.grammar.Python3Lexer
import de.jplag.python3.grammar.Python3Parser
import org.antlr.v4.runtime.BailErrorStrategy
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonToken
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.DefaultErrorStrategy
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.ATN
import org.antlr.v4.runtime.atn.LexerATNSimulator
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.ParseTreeWalker
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Python, through JPlag's own Python grammar and token-mapping listener, run from a string rather than a
 * file ([InMemoryPythonAdapter]).
 */
internal object PythonFrontend : SimilarityFrontend {

    override fun read(id: File, source: String, maxTokens: Int): Tokenised =
        InMemoryPythonAdapter().read(id, asPythonReadsIt(source), maxTokens)

    /**
     * [source] with what the grammar reads differently from CPython made the same: a trailing newline,
     * which the grammar wants, and form feeds, which it reads as line ends where CPython reads a space -
     * a few inserted into a copy, invisible in an editor, made a verbatim copy score 20%.
     */
    private fun asPythonReadsIt(source: String): String {
        val text = if ('\u000C' in source) source.replace(FORM_FEED_IN_INDENT, "").replace('\u000C', ' ') else source
        return text.trimEnd() + "\n"
    }

    /** A line's indentation up to and including its last form feed, which CPython measures from. */
    private val FORM_FEED_IN_INDENT = Regex("(?m)^[ \\t\\u000C]*\\u000C")
}

/**
 * ANTLR's lexer simulator, caching the one start state the grammar stops it from caching: tokenising is
 * about seven times faster with it (17-24 ms/KB without, 2.5-3.0 with).
 *
 * The grammar's NEWLINE rule begins with the predicate `atStartOfInput()`, and ANTLR does not cache a start
 * state whose closure passed through a predicate. That predicate is true only at line 1, column 0, so past
 * there the start state is the same every time and safe to cache; at the start of input it is built fresh
 * and not stored. A closure through any other predicate - a grammar upgrade might add one - is not cached.
 */
internal class StartStateCachingLexerATNSimulator(
    recog: Lexer,
    atn: ATN,
    decisionToDfa: Array<DFA>,
    contextCache: PredictionContextCache,
) : LexerATNSimulator(recog, atn, decisionToDfa, contextCache) {

    /** Whether the closure being built evaluated a predicate other than NEWLINE's. */
    private var otherPredicate = false

    private fun atStartOfInput() = line == 1 && charPositionInLine == 0

    override fun match(input: CharStream, mode: Int): Int {
        if (!atStartOfInput()) return super.match(input, mode)
        // super.match() without its read of the cached start state, which was built where the predicate is false.
        this.mode = mode
        val mark = input.mark()
        try {
            startIndex = input.index()
            val closure = computeStartState(input, atn.modeToStartState[mode])
            closure.hasSemanticContext = false
            return execATN(input, addDFAState(closure))
        } finally {
            input.release(mark)
        }
    }

    override fun evaluatePredicate(input: CharStream, ruleIndex: Int, predIndex: Int, speculative: Boolean): Boolean {
        if (!(ruleIndex == NEWLINE_RULE && predIndex == 0)) otherPredicate = true
        return super.evaluatePredicate(input, ruleIndex, predIndex, speculative)
    }

    // Reached only past the start of input. ANTLR's own matchATN, but caching unless another predicate was met.
    override fun matchATN(input: CharStream): Int {
        otherPredicate = false
        val closure = computeStartState(input, atn.modeToStartState[mode])
        closure.hasSemanticContext = false
        val s0 = addDFAState(closure)
        if (!otherPredicate) decisionToDFA[mode].s0 = s0
        return execATN(input, s0)
    }

    private companion object {
        val NEWLINE_RULE = Python3Lexer.ruleNames.indexOf("NEWLINE")
    }
}

/**
 * The lexer DFA every tokenisation shares, and not the generated lexer's own: it holds a start state that is
 * right only past the start of input, which a stock lexer would read at the start too. Shared across threads
 * as ANTLR's own DFAs are; ANTLR locks what it writes.
 */
internal object PythonLexerCache {
    val decisionToDfa: Array<DFA> = Python3Lexer._ATN.let { atn ->
        Array(atn.numberOfDecisions) { DFA(atn.getDecisionState(it), it) }
    }
    val contextCache = PredictionContextCache()
}

/**
 * The parser's prediction cache, shared by every parse but replaced by an empty one once it holds
 * [MAX_STATES] states. Prediction on deeply mixed brackets adds a state for each distinct stack of rules, and
 * kept for good, a few such submissions held hundreds of MB of core's heap until a restart.
 */
internal object PythonParserCache {
    class Generation {
        val decisionToDfa: Array<DFA> = Python3Parser._ATN.let { atn ->
            Array(atn.numberOfDecisions) { DFA(atn.getDecisionState(it), it) }
        }
        val contextCache = PredictionContextCache()
        val states = AtomicInteger()
    }

    const val MAX_STATES = 30_000

    @Volatile
    var current = Generation()
        private set

    fun retireIfFull(generation: Generation) {
        if (generation.states.get() <= MAX_STATES || current !== generation) return
        synchronized(this) {
            if (current === generation) current = Generation()
        }
    }
}

/** The most states one parse may add to the prediction cache; see [PythonParserCache]. */
private const val MAX_NEW_DFA_STATES = 5_000

/** Wall clock for one solution's parse, and again for its tree walk: ordinary code takes tens of milliseconds. */
private const val PARSE_TIME_LIMIT_MS = 2_000L

/**
 * CPython's own limit on bracket nesting, so nothing that runs is refused by it. Deeper brackets take the
 * parser minutes inside the token cap.
 */
private const val MAX_BRACKET_NESTING = 200

private val LAYOUT_TOKENS = setOf(Python3Lexer.NEWLINE, Python3Lexer.INDENT, Python3Lexer.DEDENT, Token.EOF)

/** The lexical tokens: what the parser is handed, less the line ends, indentation and end the grammar makes. */
private fun lexicalTokenCount(tokens: List<Token>): Int =
    tokens.count { it.channel == Token.DEFAULT_CHANNEL && it.type !in LAYOUT_TOKENS }

/** How deeply the brackets nest, all three kinds together, as CPython counts them. */
private fun bracketNesting(tokens: List<Token>): Int {
    var depth = 0
    var deepest = 0
    for (token in tokens) when (token.type) {
        Python3Lexer.OPEN_PAREN, Python3Lexer.OPEN_BRACK, Python3Lexer.OPEN_BRACE -> deepest = maxOf(deepest, ++depth)
        Python3Lexer.CLOSE_PAREN, Python3Lexer.CLOSE_BRACK, Python3Lexer.CLOSE_BRACE -> if (depth > 0) depth--
    }
    return deepest
}

/**
 * JPlag's Python lexer, with each NEWLINE put back on the line it ends. The grammar builds NEWLINE on the
 * lexer's current line - already the next code line - and a match ending on a one-line body (`if x: y`)
 * then highlighted the line below it. Only line numbers move, never scores.
 */
private class LineTruePython3Lexer(input: CharStream) : Python3Lexer(input) {
    private var lastCodeLine = 0

    init {
        interpreter = StartStateCachingLexerATNSimulator(this, atn, PythonLexerCache.decisionToDfa, PythonLexerCache.contextCache)
    }

    override fun emit(token: Token) {
        when (token.type) {
            Python3Lexer.NEWLINE -> if (lastCodeLine > 0 && token is CommonToken) token.line = lastCodeLine
            Python3Lexer.INDENT, Python3Lexer.DEDENT, Token.EOF -> Unit
            else -> if (token.channel == Token.DEFAULT_CHANNEL) lastCodeLine = lastLineOf(token)
        }
        super.emit(token)
    }

    // Only a string can span lines.
    private fun lastLineOf(token: Token): Int =
        if (token.type == Python3Lexer.STRING) token.line + token.text.count { it == '\n' } else token.line
}

/**
 * [PythonParserAdapter]'s protected grammar wiring, run from a string. Syntax errors are not failures:
 * ANTLR recovers, as JPlag's own path does, and rejecting a solution for one would let a copier opt out
 * with one line the grammar does not know.
 */
private class InMemoryPythonAdapter : PythonParserAdapter() {

    override fun createLexer(input: CharStream): Lexer = LineTruePython3Lexer(input)

    fun read(id: File, source: String, maxTokens: Int): Tokenised {
        // ANTLR's default listener prints every recovered error to stderr; they are only counted here.
        val lexer = createLexer(CharStreams.fromString(source, id.path))
        lexer.removeErrorListeners()
        val errors = ErrorLines()
        lexer.addErrorListener(errors)
        // Lexed in full first, so the caps are on what the parser would be handed. Lexing is linear;
        // parsing is where the cost is.
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        if (lexicalTokenCount(tokens.tokens) > maxTokens) throw SolutionTooLarge(maxTokens)
        val nesting = bracketNesting(tokens.tokens)
        if (nesting > MAX_BRACKET_NESTING) {
            throw SolutionTooComplex("Brackets nest $nesting deep, above the $MAX_BRACKET_NESTING CPython allows")
        }
        val parser: Python3Parser = createParser(tokens)
        parser.removeErrorListeners()
        parser.addErrorListener(errors)
        val cache = PythonParserCache.current
        parser.interpreter = BudgetedParserATNSimulator(
            parser, parser.atn, cache.decisionToDfa, cache.contextCache,
            TimeUnit.MILLISECONDS.toNanos(PARSE_TIME_LIMIT_MS),
            cache.states, MAX_NEW_DFA_STATES,
        )
        val tree = try {
            parse(parser, tokens)
        } finally {
            PythonParserCache.retireIfFull(cache)
        }
        val walker = TimedTreeWalker(TimeUnit.MILLISECONDS.toNanos(PARSE_TIME_LIMIT_MS))
        return Tokenised(
            InMemoryTokenCollectorAccess.collect(id, tree, getListener(), false, walker),
            recovered = mostlyRecovered(errors.lines, source),
        )
    }

    /**
     * SLL prediction first, full LL only if SLL fails. A successful SLL parse is the tree LL would build, and
     * SLL keeps valid deep nesting from costing minutes; LL, with error recovery as JPlag parses, decides the rest.
     */
    private fun parse(parser: Python3Parser, tokens: CommonTokenStream): ParserRuleContext {
        parser.interpreter.predictionMode = PredictionMode.SLL
        parser.errorHandler = BailErrorStrategy()
        try {
            return getEntryContext(parser)
        } catch (_: ParseCancellationException) {
            tokens.seek(0)
            parser.reset()
        }
        parser.interpreter.predictionMode = PredictionMode.LL
        parser.errorHandler = DefaultErrorStrategy()
        return getEntryContext(parser)
    }
}

/** Whether syntax errors are on more than half of [source]'s non-blank lines, the lines in [errorLines]. */
private fun mostlyRecovered(errorLines: Set<Int>, source: String): Boolean =
    errorLines.size * 2 > source.lineSequence().count { it.isNotBlank() }

/** The lines the lexer and the parser reported syntax errors on. */
private class ErrorLines : BaseErrorListener() {
    val lines = HashSet<Int>()

    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String?,
        e: RecognitionException?,
    ) {
        lines += line
    }
}

/**
 * ANTLR's tree walk, held to a wall-clock limit like the parse. JPlag's listener asks nodes for their text,
 * which ANTLR rebuilds from the children each time, so deep nesting around a long string walked for 26 s.
 */
private class TimedTreeWalker(private val limitNanos: Long) : ParseTreeWalker() {
    private val deadline = Deadline.after(limitNanos)

    override fun walk(listener: ParseTreeListener, t: ParseTree) {
        if (deadline.passed()) throw SolutionTooComplex("Walking the parse took more than ${limitNanos / 1_000_000} ms")
        super.walk(listener, t)
    }
}
