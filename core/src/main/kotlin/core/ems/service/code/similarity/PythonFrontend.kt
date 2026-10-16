package core.ems.service.code.similarity

import de.jplag.Token
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
import org.antlr.v4.runtime.atn.ATN
import org.antlr.v4.runtime.atn.LexerATNSimulator
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.atn.PredictionMode
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.dfa.DFAState
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.ParseTree
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.ParseTreeWalker
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Python, through JPlag's own Python module: its ANTLR grammar and its token-mapping listener, run
 * from a string (see [InMemoryPythonAdapter]).
 */
internal object PythonFrontend : SimilarityFrontend {

    /**
     * 6, where JPlag's Python default is 12.
     *
     * 12 is tuned for programs much larger than Lahendus sets: an exercise like "read two numbers and
     * print the sum" is about ten tokens end to end, so at 12 nothing ever matched and every pair
     * scored zero. 6 is calibrated, not guessed: doc/jplag-integration.md carries the sweep. No value
     * separates copying from honest refactoring, and none makes a three-line exercise safe without
     * making a nine-line one undetectable, so 6 is the end of that trade-off where a real exercise is
     * still covered.
     */
    override val minimumTokenMatch = 6

    override fun read(id: File, source: String, maxTokens: Int): Tokenised =
        InMemoryPythonAdapter().read(id, asPythonReadsIt(source), maxTokens)

    /**
     * Tokens by LL prediction alone - the parse JPlag itself does - for the test that holds the
     * SLL-first parse to it. Not for anything else: it has no fast path for deep nesting.
     */
    internal fun tokeniseWithLlOnly(id: File, source: String): List<Token> =
        InMemoryPythonAdapter().read(id, asPythonReadsIt(source), Int.MAX_VALUE, sllFirst = false).tokens

    /**
     * [source] with what JPlag's grammar reads differently from CPython made the same.
     *
     * The grammar wants a trailing newline and reports an error at EOF without one; `submission.solution`
     * is a text column holding whatever the student's editor left, so it is normalised rather than assumed.
     *
     * And the grammar ends a line at a form feed (its NEWLINE is `'\r'? '\n' | '\r' | '\f'`), where CPython
     * reads one inside a line as a space and one in a line's indentation as the point the indentation is
     * measured from. A few form feeds inserted into a copy - the same program to Python, and invisible in
     * most editors - broke its lines and blocks apart: a verbatim copy scored 20%.
     */
    private fun asPythonReadsIt(source: String): String {
        val text = if ('\u000C' in source) source.replace(FORM_FEED_IN_INDENT, "").replace('\u000C', ' ') else source
        return text.trimEnd() + "\n"
    }

    /** A line's indentation up to and including its last form feed, which CPython measures from. */
    private val FORM_FEED_IN_INDENT = Regex("(?m)^[ \\t\\u000C]*\\u000C")
}

/**
 * ANTLR's lexer simulator, caching the one state JPlag's Python grammar stops it from caching.
 *
 * Tokenising measured 17-24 ms/KB, and 80-90% of that was the lexer, which is some hundred times
 * slower than an ANTLR lexer runs. The cause is one predicate. The grammar's NEWLINE rule begins
 * `{this.atStartOfInput()}? SPACES`, which puts the predicate in the closure of the lexer's start
 * state, and ANTLR refuses to cache a start state whose closure passed through a predicate - the
 * answer might differ next time. So every token rebuilt that closure across all ~100 lexer rules and
 * looked the result up in the DFA under a lock on `dfa.states`.
 *
 * The predicate reads nothing but the lexer's position, and is true only at line 1, column 0. Past
 * that its answer is always false, so the start state built there is the same every time and safe to
 * cache; at the start of input the state is built fresh and never stored. Measured on 500 generated
 * solutions (1.7 MB): lexing from 14-25 ms/KB to about 0.1, tokenising as a whole from 17-24 to
 * 2.5-3.0 ms/KB, and identical tokens - type, line, column, channel, offsets and text - on 577 inputs.
 *
 * That argument is about that one predicate, so the cache is only used for a start state whose closure
 * evaluated no other: a grammar that gains one - a JPlag upgrade, say - gets ANTLR's own uncached
 * behaviour for it, rather than a cached state that is wrong for some inputs.
 *
 * It overrides protected ANTLR internals (`match`, `matchATN`, `evaluatePredicate`, `computeStartState`,
 * `addDFAState`, `execATN`), which is why `PythonFrontendTest` holds its output to the stock lexer's: an
 * ANTLR upgrade that changes those fails the test rather than quietly changing what gets compared.
 */
internal class StartStateCachingLexerATNSimulator(
    recog: Lexer,
    atn: ATN,
    decisionToDfa: Array<DFA>,
    contextCache: PredictionContextCache,
    /**
     * The predicates, by rule and predicate index, known to be false everywhere past the start of
     * input: NEWLINE's, and only in the grammar this was written for. A parameter so that a test can
     * trust none and see the uncached path, which no grammar JPlag ships reaches.
     */
    private val falsePastStart: (ruleIndex: Int, predIndex: Int) -> Boolean = { rule, pred -> rule == NEWLINE_RULE && pred == 0 },
) : LexerATNSimulator(recog, atn, decisionToDfa, contextCache) {

    /** Whether the closure being built evaluated a predicate other than NEWLINE's; see [matchATN]. */
    private var otherPredicate = false

    /** What the grammar's `atStartOfInput()` tests, read where it reads it: the lexer's position is this simulator's. */
    private fun atStartOfInput() = line == 1 && charPositionInLine == 0

    override fun match(input: CharStream, mode: Int): Int {
        if (!atStartOfInput()) return super.match(input, mode)
        // super.match() minus its read of the cached start state. That state was built where the
        // predicate is false, and used here it would lex a first line's indentation as whitespace
        // rather than as the NEWLINE the grammar makes of it. prevAccept.reset() is the one statement
        // left out: it is protected in SimState, and moot here, because no match has run on this
        // input yet - every match consumes a character, and reset() resets prevAccept with the position.
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
        if (!falsePastStart(ruleIndex, predIndex)) otherPredicate = true
        return super.evaluatePredicate(input, ruleIndex, predIndex, speculative)
    }

    // Reached only through super.match(), so only past the start of input, where NEWLINE's predicate
    // is always false. ANTLR's own matchATN, but for when it caches: always, unless the closure went
    // through some other predicate, when it does exactly what ANTLR does and caches nothing.
    override fun matchATN(input: CharStream): Int {
        otherPredicate = false
        val closure = computeStartState(input, atn.modeToStartState[mode])
        closure.hasSemanticContext = false
        val s0: DFAState = addDFAState(closure)
        if (!otherPredicate) decisionToDFA[mode].s0 = s0
        return execATN(input, s0)
    }

    private companion object {
        /** The rule whose predicate is known safe to cache past; -1, if a grammar ever drops it, caches nothing. */
        val NEWLINE_RULE = Python3Lexer.ruleNames.indexOf("NEWLINE")
    }
}

/**
 * The lexer DFA every tokenisation shares: one for the process, and deliberately not the generated
 * lexer's own static one.
 *
 * [StartStateCachingLexerATNSimulator] stores a start state that is right only past the start of
 * input. A stock `Python3Lexer` on the same DFA would read it at the start as well - JPlag's own
 * adapter builds exactly that lexer - so the stock lexer keeps its own DFA and behaves as it did.
 *
 * Shared across threads on purpose, as ANTLR's own static DFAs are. States are added under a lock on
 * `dfa.states`, edges are written under a lock on their source state and read without one (ANTLR's
 * documented design: a reader that misses an edge computes it again), and `s0` is volatile. Per-thread
 * caches measured slower once the start state was cached: rebuilt for every run, they always start
 * cold. Four threads took 4.0 s over the same 500 solutions with them and 1.7 s on one shared cache
 * already warm. [contextCache] is here because the simulator's constructor takes one; a lexer never
 * reads it.
 */
internal object PythonLexerCache {
    val decisionToDfa: Array<DFA> = Python3Lexer._ATN.let { atn ->
        Array(atn.numberOfDecisions) { DFA(atn.getDecisionState(it), it) }
    }
    val contextCache = PredictionContextCache()

    /** Whether a start state has been cached, which is to say whether the fast path is the one in use. */
    val startStateCached: Boolean get() = decisionToDfa[Lexer.DEFAULT_MODE].s0 != null
}

/**
 * The parser's prediction cache, shared by every parse as ANTLR intends, but not for ever: replaced by an
 * empty one once it holds [MAX_STATES] states.
 *
 * It used to be the generated parser's own static DFA, kept for the life of the process, and prediction
 * on deeply mixed brackets adds a state for each distinct stack of rules it passes through. The states
 * outlived the parse that was refused for them, so each such submission - valid Python, under CPython's
 * limit on nesting and under the token cap - kept about 120 MB of core's heap for good, and a few
 * resubmissions ahead of a teacher's runs were an OutOfMemoryError for everyone until a restart. Now one
 * parse may add at most [MAX_NEW_DFA_STATES] ([BudgetedParserATNSimulator] refuses it past that), and a
 * cache grown past [MAX_STATES] goes once the parses using it are done.
 *
 * Both are far above what code does: 500 generated solutions added 2,057 states between them on one
 * cache, the most in any one parse 439 on a cold cache and 619 on a fresh one; the brackets above added
 * 210,476 in one parse, left alone. A replaced cache costs only a cold start.
 */
internal object PythonParserCache {
    /** One cache: the DFA every parse predicts with, its contexts, and how many states it holds. */
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

    /** Replaces [generation] with an empty cache if it is still the one in use and has grown past [MAX_STATES]. */
    fun retireIfFull(generation: Generation) {
        if (generation.states.get() <= MAX_STATES || current !== generation) return
        synchronized(this) {
            if (current === generation) current = Generation()
        }
    }
}

/** The most states one parse may add to the prediction cache; see [PythonParserCache]. */
internal const val MAX_NEW_DFA_STATES = 5_000

/**
 * [BudgetedParserATNSimulator]'s budget for a parse of n lexer tokens: this plus [PARSE_BUDGET_PER_TOKEN]
 * times n. Over the worst cold-DFA cost measured on ordinary code by at least 12 times for any solution
 * of a few thousand tokens, and still twice it at the default token cap. It stops a pathological parse
 * of moderate depth within about a second; past that, [PARSE_TIME_LIMIT_MS] is what holds.
 */
private const val PARSE_BUDGET_FLOOR = 1_000_000L
private const val PARSE_BUDGET_PER_TOKEN = 500L

/**
 * The wall-clock limit on one solution's parse. Ordinary code, cold DFA or not, parses in tens of
 * milliseconds, and the slowest legitimate parse measured - the deepest nesting CPython accepts, of
 * each kind - in about a second; this refuses only what the prediction budget would otherwise let run
 * for a minute. Java's tree walk is held to the same limit (see JavaFrontend).
 */
internal const val PARSE_TIME_LIMIT_MS = 2_000L

/**
 * Deepest bracket nesting a solution may have and still be parsed: CPython's own limit, so nothing
 * that can run is refused by it.
 *
 * Deep brackets are the other thing that costs the parser without costing length: at a nesting of
 * 2,000, `f(f(f(…)))` took 44 s to parse and 5,000 of `[` or `(` over two minutes, SLL or not, inside
 * the token cap. On a default 1 MB stack that nesting overflowed within a second, which is how it used
 * to end - but the pool's stack is now large enough for the deep nesting CPython does accept (see
 * SimilarityService.WORKER_STACK_BYTES), so the overflow no longer comes to the rescue, and this does.
 * CPython refuses more than 200 with "too many nested parentheses", so nothing deeper can pass a test.
 */
private const val MAX_BRACKET_NESTING = 200

/**
 * What the token cap counts: every token the parser is handed but the line ends, indentation and end of
 * input the grammar makes - the lexical tokens every other frontend counts too.
 */
private fun lexicalTokenCount(tokens: List<org.antlr.v4.runtime.Token>): Int = tokens.count {
    it.channel == org.antlr.v4.runtime.Token.DEFAULT_CHANNEL && it.type !in LAYOUT_TOKENS
}

private val LAYOUT_TOKENS = setOf(
    Python3Lexer.NEWLINE, Python3Lexer.INDENT, Python3Lexer.DEDENT, org.antlr.v4.runtime.Token.EOF,
)

/** How deeply the brackets in [tokens] nest, of all three kinds together, as CPython counts them. */
private fun bracketNesting(tokens: List<org.antlr.v4.runtime.Token>): Int {
    var depth = 0
    var deepest = 0
    for (token in tokens) when (token.type) {
        Python3Lexer.OPEN_PAREN, Python3Lexer.OPEN_BRACK, Python3Lexer.OPEN_BRACE -> deepest = maxOf(deepest, ++depth)
        Python3Lexer.CLOSE_PAREN, Python3Lexer.CLOSE_BRACK, Python3Lexer.CLOSE_BRACE -> if (depth > 0) depth--
    }
    return deepest
}

/**
 * JPlag's Python lexer, with each NEWLINE put back on the line it ends.
 *
 * `Python3LexerBase` builds NEWLINE through `CommonToken(source, …)`, which reads the lexer's
 * *current* line — already past the newline, and past any blank or comment lines it skipped — so the
 * token lands on the next code line. JPlag corrects DEDENT the same way (`createDedent`), but not
 * NEWLINE, and NEWLINE is the stop token of every one-line body: `for …: x`, `if …: x`, `def f(): x`.
 * JPlag takes a block's END token from that stop, so a match ending on one reported the line below
 * it as matched, and the page painted a line neither student shares. Scores are unaffected; only the
 * line numbers move, onto the line the last code token before the NEWLINE ends on. That is its own
 * line for everything but a string literal, which can span lines and ends the statement on its last.
 */
private class LineTruePython3Lexer(input: CharStream) : Python3Lexer(input) {
    private var lastCodeLine = 0

    init {
        interpreter = StartStateCachingLexerATNSimulator(
            this, atn, PythonLexerCache.decisionToDfa, PythonLexerCache.contextCache,
        )
    }

    override fun emit(token: org.antlr.v4.runtime.Token) {
        when (token.type) {
            Python3Lexer.NEWLINE -> if (lastCodeLine > 0 && token is CommonToken) token.line = lastCodeLine
            Python3Lexer.INDENT, Python3Lexer.DEDENT, org.antlr.v4.runtime.Token.EOF -> Unit
            else -> if (token.channel == org.antlr.v4.runtime.Token.DEFAULT_CHANNEL) lastCodeLine = lastLineOf(token)
        }
        super.emit(token)
    }

    // Only strings read their text: it is a substring per token, and nothing else can span lines.
    private fun lastLineOf(token: org.antlr.v4.runtime.Token): Int =
        if (token.type == Python3Lexer.STRING) token.line + token.text.count { it == '\n' } else token.line
}

/**
 * Reaches [PythonParserAdapter]'s protected grammar wiring so the parse runs from a String.
 *
 * `createLexer`, `createParser`, `getEntryContext` and `getListener` are all `protected`; only
 * `parseFile`, the one method that opens a file, is private. So a subclass gets the real Python
 * grammar *and* JPlag's token-mapping listener — the linguistically meaningful part — without a
 * filesystem anywhere in the path.
 *
 * Syntax errors are deliberately **not** treated as failure. ANTLR recovers from them and JPlag's
 * own file-based path does the same: its listener only logs, `parseFile` returns normally, and the
 * submission is compared. Rejecting a submission for a recovered error would be stricter than JPlag
 * and would hand a plagiarist an opt-out — append one line the 2016-era Python grammar dislikes and
 * be excluded from detection.
 */
private class InMemoryPythonAdapter : PythonParserAdapter() {

    override fun createLexer(input: CharStream): Lexer = LineTruePython3Lexer(input)

    fun read(id: File, source: String, maxTokens: Int, sllFirst: Boolean = true): Tokenised {
        // ANTLR attaches ConsoleErrorListener by default, which System.err.printlns every recovered
        // syntax error. JPlag's own parseFile removes it for the same reason. Beginner code has
        // recovered errors constantly, and untimestamped stderr that names no submission is worse
        // than silence - the errors are not acted on either way, since ANTLR recovers and we compare.
        // They are only counted: the lexer's here, the parser's by the parser itself.
        //
        // Each listener is dropped as soon as its object exists, rather than once both do: the lexer
        // reports its errors while fill() below runs, which is before the parser exists at all.
        val lexer = createLexer(CharStreams.fromString(source, id.path))
        lexer.removeErrorListeners()
        val errors = ErrorLines()
        lexer.addErrorListener(errors)
        // Lexed in full before any of it is parsed, so the cap can be on what the parser would be
        // handed. Lexing is linear and, with the start state cached, about 0.1 ms/KB; parsing is where
        // the cost is and where it goes superlinear. Comments and blank lines are skipped by the lexer
        // and so do not count - under the character cap this replaced, a copy padded with one long
        // comment was TOO_LARGE, out of the comparison while running exactly as before.
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
        // The shared prediction cache, and a simulator around it that counts what one parse may cost.
        val cache = PythonParserCache.current
        parser.interpreter = BudgetedParserATNSimulator(
            parser, parser.atn, cache.decisionToDfa, cache.contextCache,
            PARSE_BUDGET_FLOOR + PARSE_BUDGET_PER_TOKEN * tokens.size(),
            TimeUnit.MILLISECONDS.toNanos(PARSE_TIME_LIMIT_MS),
            cache.states, MAX_NEW_DFA_STATES,
        )
        val tree = try {
            parse(parser, tokens, sllFirst)
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
     * SLL prediction first, full LL only if SLL fails.
     *
     * ANTLR guarantees that an SLL parse which succeeds is the tree LL would have built; one that fails
     * means either a syntax error or an input that needs LL's extra power, and LL - with error recovery,
     * as JPlag itself parses - then decides. None of 500 generated solutions needed the second stage,
     * and SLL is what keeps valid deep nesting from costing minutes; see [BudgetedParserATNSimulator].
     * The budget counts both stages together, since the simulator is one object across the reset. The
     * reset also zeroes the parser's count of syntax errors, so what is left is the LL parse's: an SLL
     * failure may be SLL's own weakness rather than the code's.
     */
    private fun parse(parser: Python3Parser, tokens: CommonTokenStream, sllFirst: Boolean): ParserRuleContext {
        if (sllFirst) {
            parser.interpreter.predictionMode = PredictionMode.SLL
            parser.errorHandler = BailErrorStrategy()
            try {
                return getEntryContext(parser)
            } catch (_: ParseCancellationException) {
                tokens.seek(0)
                parser.reset()
            }
        }
        parser.interpreter.predictionMode = PredictionMode.LL
        parser.errorHandler = DefaultErrorStrategy()
        return getEntryContext(parser)
    }
}

/** The lines the lexer and the parser reported syntax errors on. The SLL stage bails without reporting. */
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
 * ANTLR's tree walk, held to a wall-clock limit like the parse before it.
 *
 * JPlag's Python listener decides some tokens by `getText()` - whether a trailer opens with `(`, whether
 * a bracketed list has a comma - and ANTLR builds that text by concatenating its children's, level by
 * level, so each call costs the node's text times the depth under it, and the listener asks both on
 * entering a node and on leaving it. 199 nested calls around one 120 KB string passed every cap, parsed
 * in a fifth of a second and then walked for 26 s. The clock is read before each node, so what one node's
 * own questions cost is the most the limit can be overrun by.
 */
private class TimedTreeWalker(private val limitNanos: Long) : ParseTreeWalker() {
    private val deadline = Deadline.after(limitNanos)

    override fun walk(listener: ParseTreeListener, t: ParseTree) {
        if (deadline.passed()) throw SolutionTooComplex("Walking the parse took more than ${limitNanos / 1_000_000} ms")
        super.walk(listener, t)
    }
}
