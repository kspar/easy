package core.ems.service.code.similarity

import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.atn.ATN
import org.antlr.v4.runtime.atn.ATNConfig
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.atn.ATNSimulator
import org.antlr.v4.runtime.atn.ParserATNSimulator
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.dfa.DFA
import org.antlr.v4.runtime.dfa.DFAState
import java.util.concurrent.atomic.AtomicInteger

/**
 * ANTLR's parser simulator with a ceiling on the prediction work one parse may do. Written for no
 * grammar in particular; Python's is the one that needs it (see PythonFrontend).
 *
 * Under full LL prediction, JPlag's Python grammar goes superlinear on nested unary operators: `x = `
 * and 500 minus signs took 18 s to parse and 1,000 of them over two minutes - 506 characters, inside
 * any size cap, and valid Python up to 5,966. SLL prediction parses the same line in a millisecond, so
 * the Python frontend tries it first and LL runs only on the fallback, for input SLL cannot parse -
 * which a student can always write, with one line of syntax the 2016-era grammar does not know. This
 * is the bound on that path, and a parse cannot otherwise be cut short: ANTLR never polls.
 *
 * Counted in closure operations, the unit of prediction work. Ordinary code does 2-14 per lexer token
 * on a warm DFA and at most 280 or so on a cold one, broken code or not; the nested minuses above do
 * quadratically many, a million at depth 100 and ten million at 300.
 *
 * A count alone does not bound time, though, because a closure costs more the deeper the nesting it
 * works through: at CPython's limit of 5,966 minuses the count was reached only after 67 s, with the
 * heap at 1.3 GB. So the parse is also held to [timeLimitNanos] of wall clock, read every 1,024
 * closures - a few milliseconds apart even at that depth. Every other deep construct measured,
 * `not`, `lambda`, `**`, conditional and bracketed alike, parses under LL in about a second at
 * CPython's limit, well inside it.
 *
 * And a count of neither bounds memory, because the DFA the parse extends is shared and outlives it:
 * prediction on deeply mixed brackets adds a state for each distinct stack of rules it goes through,
 * and one such parse added 210,476, about 120 MB, all of it kept after the parse was refused. So a parse
 * may also add no more than [maxNewStates] states, counted into [sharedStates], the total its cache's
 * owner decides by when to replace it (see PythonParserCache).
 *
 * Past any of these the parse is abandoned by throwing, which is safe: the DFA is extended only with
 * finished closures and complete states, and adaptivePredict's own `finally` puts the token stream back.
 */
internal class BudgetedParserATNSimulator(
    parser: Parser,
    atn: ATN,
    decisionToDfa: Array<DFA>,
    contextCache: PredictionContextCache,
    private val budget: Long,
    private val timeLimitNanos: Long,
    private val sharedStates: AtomicInteger,
    private val maxNewStates: Int,
) : ParserATNSimulator(parser, atn, decisionToDfa, contextCache) {
    private var closures = 0L
    private var newStates = 0
    private val deadline = Deadline.after(timeLimitNanos)

    override fun addDFAState(dfa: DFA, D: DFAState): DFAState {
        val state = super.addDFAState(dfa, D)
        // ANTLR hands back the state it already had, or adds this one and hands it back.
        if (state === D && D !== ATNSimulator.ERROR) {
            sharedStates.incrementAndGet()
            if (++newStates > maxNewStates) {
                throw SolutionTooComplex("Parsing added more than $maxNewStates states to the shared prediction cache")
            }
        }
        return state
    }

    override fun closure_(
        config: ATNConfig,
        configs: ATNConfigSet,
        closureBusy: MutableSet<ATNConfig>,
        collectPredicates: Boolean,
        fullCtx: Boolean,
        depth: Int,
        treatEofAsEpsilon: Boolean,
    ) {
        if (++closures > budget) throw SolutionTooComplex("Parsing took more than $budget prediction steps")
        if ((closures and CLOCK_EVERY) == 0L && deadline.passed()) {
            throw SolutionTooComplex("Parsing took more than ${timeLimitNanos / 1_000_000} ms")
        }
        super.closure_(config, configs, closureBusy, collectPredicates, fullCtx, depth, treatEofAsEpsilon)
    }

    private companion object {
        /** Closures between two reads of the clock, less one: a mask, so the check is a single AND. */
        const val CLOCK_EVERY = 1_023L
    }
}
