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
 * ANTLR's parser simulator with ceilings on what one parse may cost, since ANTLR never polls and a parse
 * cannot otherwise be cut short. Under full LL prediction, Python's grammar goes superlinear on nested
 * unary operators: `x = ` and 1,000 minus signs, valid Python, took over two minutes.
 *
 * - [timeLimitNanos] of wall clock, read every 1,024 closure operations, the unit of prediction work;
 * - [maxNewStates] states added to the shared prediction cache, counted into [sharedStates] (see PythonParserCache).
 *
 * Past either the parse throws [SolutionTooComplex]. That is safe: the cache is extended only with
 * finished states.
 */
internal class BudgetedParserATNSimulator(
    parser: Parser,
    atn: ATN,
    decisionToDfa: Array<DFA>,
    contextCache: PredictionContextCache,
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
        if ((++closures and 1_023L) == 0L && deadline.passed()) {
            throw SolutionTooComplex("Parsing took more than ${timeLimitNanos / 1_000_000} ms")
        }
        super.closure_(config, configs, closureBusy, collectPredicates, fullCtx, depth, treatEofAsEpsilon)
    }
}
