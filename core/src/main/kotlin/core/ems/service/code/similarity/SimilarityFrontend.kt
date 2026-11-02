package core.ems.service.code.similarity

import de.jplag.SharedTokenType
import de.jplag.Token
import java.io.File

/**
 * One language's way from a solution's text to the tokens JPlag matches: [PythonFrontend], or
 * [TextFrontend] for words.
 *
 * - **The tokens end in [SharedTokenType.FILE_END].** It is the only type excluded from matching, and JPlag's
 *   tiling relies on it to stop a match at the end of the list; without it the comparison walks off the
 *   array, and every score is inflated by one token.
 * - **At most `maxTokens` lexical tokens** - names, words, literals and symbols, not comments - are read;
 *   above that [SolutionTooLarge] is thrown before anything costlier runs. Comments do not count, so padding
 *   a copy with one long comment cannot take it out of the comparison.
 * - **[SolutionTooComplex] for an input refused on cost**, and any other exception for one that cannot be read.
 * - **Safe to call from several threads at once**, on the workers' deep stacks.
 */
internal interface SimilarityFrontend {

    /** Tokens for one solution; [id] identifies the tokens and is never opened. */
    fun read(id: File, source: String, maxTokens: Int): Tokenised
}

/**
 * One solution's tokens, and whether the parser had to recover from syntax errors on most of its lines. One
 * such solution is compared like any other - excluding it would let a copier opt out with one broken line -
 * but a cohort of them is not in the language at all: prose in an exercise that kept `lahendus.py`, whose
 * recovered parse made each line one identical token, and unrelated essays paired at 90-100%.
 */
internal class Tokenised(val tokens: List<Token>, val recovered: Boolean)

class SolutionTooLarge(limit: Int) : RuntimeException("Solution has more than $limit tokens")

/** The frontend declined a solution on cost, which says nothing about whether its code is sound. */
class SolutionTooComplex(message: String) : RuntimeException(message)

/**
 * A point on [System.nanoTime]'s clock, compared as `now - deadline >= 0` as it asks: its values can wrap,
 * and a budget large enough to mean "no limit" would otherwise read as already passed.
 */
internal class Deadline private constructor(private val at: Long) {
    fun passed(): Boolean = System.nanoTime() - at >= 0

    companion object {
        fun after(nanos: Long) = Deadline(System.nanoTime() + nanos)
    }
}
