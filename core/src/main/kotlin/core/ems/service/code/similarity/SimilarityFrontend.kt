package core.ems.service.code.similarity

import de.jplag.SharedTokenType
import de.jplag.Token
import java.io.File

/**
 * One language's way from a solution's text to the tokens the comparison matches: the only
 * language-specific code there is. Each frontend is registered as one [SimilarityLanguage].
 *
 * The contract, which every frontend keeps and the service relies on without checking:
 *
 *  - **The tokens end in [SharedTokenType.FILE_END].** That last token is not decoration. It is the
 *    only type whose `isExcludedFromMatching()` is true, and `GreedyStringTiling.findLongestUnmarkedMatch`
 *    advances its offset with no bounds check, so it is what stops a match at the end of the list:
 *    without it the comparison walks off the array with an `ArrayIndexOutOfBoundsException`.
 *    `Submission.getSimilarityDivisor()` also subtracts one token per file on the same assumption, so
 *    leaving it out silently inflates every score - two identical ten-word answers scored 111%.
 *  - **At most `maxTokens` lexical tokens are tokenised,** above which [SolutionTooLarge] is thrown
 *    before anything costlier than counting runs. A lexical token is a name, word, literal or symbol of
 *    the source - not a comment, not whitespace - and the count is the same unit for every language
 *    because it bounds the same thing: what the rest of the work on one solution can cost. Comments
 *    are left out so that padding a copy with one long comment cannot take it out of the comparison.
 *  - **[SolutionTooComplex] for an input refused on cost,** which says nothing about whether its code
 *    is sound, and any other exception for one that cannot be tokenised; see [SimilarityLanguage.tokenise]
 *    for how each is filed.
 *  - **Safe to call from several threads at once,** on threads with a deep stack: the service
 *    tokenises a cohort in parallel on its own pool (see SimilarityWorkers).
 *  - **Whether the parser had to recover** from a syntax error is reported, not acted on (see
 *    [Tokenised.recovered]): one solution that needed it is compared like any other.
 */
internal interface SimilarityFrontend {

    /**
     * The shortest run of this frontend's tokens that counts as a match.
     *
     * The single number that decides whether the comparison is useful, and a property of the token -
     * of what one means and how many a line of code makes - rather than of an installation, so it is
     * set here rather than configured. Too high and a short exercise never matches at all; too low and
     * a whole cohort shares one correct token stream. Each value is calibrated, and
     * doc/jplag-integration.md carries the sweep; re-measure before moving one.
     */
    val minimumTokenMatch: Int

    /**
     * Whether this frontend can run in this JVM at all. A language whose frontend cannot is compared as
     * [SimilarityLanguage.TEXT] instead, and the response says so.
     */
    val available: Boolean get() = true

    /** Tokens for one solution; [id] is an identifier for the tokens to carry, never opened. */
    fun read(id: File, source: String, maxTokens: Int): Tokenised
}

/**
 * One solution's tokens, and whether its parser had to recover from a syntax error to produce them.
 *
 * One recovered solution is compared like any other - excluding it would let a copier opt out with one
 * broken line. A cohort that is mostly recovered is another matter: it is not in this language at all.
 * Every new exercise starts as `lahendus.py`, and one graded by its teacher has no grader image to say
 * otherwise, so an essay exercise's answers reached the Python parser, whose recovery made each line of
 * prose one identical token - unrelated essays paired at 90-100%. The service compares such a cohort as
 * text instead (see `SimilarityService.run`). A lexer, with nothing to recover from, says false.
 */
internal class Tokenised(val tokens: List<Token>, val recovered: Boolean)

/**
 * Whether recovery took over [source]: syntax errors on more than half of its non-blank lines, the
 * lines in [errorLines]. Prose fails on nearly every line; code fails where it uses something the
 * grammar does not know. Any error at all used to count, so a cohort whose solutions each had one valid
 * walrus read loop - unknown to JPlag's 2016 Python grammar - was compared as text, where a renamed copy
 * shares no words and was not reported.
 */
internal fun mostlyRecovered(errorLines: Set<Int>, source: String): Boolean =
    errorLines.size * 2 > source.lineSequence().count { it.isNotBlank() }

class SimilarityParseException(message: String, cause: Throwable) : RuntimeException(message, cause)

/** The tokeniser declined a solution on cost, which says nothing about whether its code is sound. */
sealed class SolutionRefused(message: String) : RuntimeException(message)

class SolutionTooLarge(val limit: Int) : SolutionRefused("Solution has more than $limit tokens")

class SolutionTooComplex(message: String) : SolutionRefused(message)

/**
 * A point on [System.nanoTime]'s clock, and whether it has passed.
 *
 * Compared the way that clock's documentation asks, `now - deadline >= 0` rather than `now >= deadline`:
 * its values can be negative and can wrap, and the plain comparison is wrong across a wrap. The
 * practical case is a budget large enough to mean "no limit", which saturates to `Long.MAX_VALUE`
 * nanoseconds and wraps the sum - refusing every run at once, where the difference reads "never".
 */
@JvmInline
internal value class Deadline private constructor(private val at: Long) {
    fun passed(): Boolean = System.nanoTime() - at >= 0

    /** How long is left, or a negative number once it has passed. */
    fun remainingNanos(): Long = at - System.nanoTime()

    companion object {
        fun after(nanos: Long) = Deadline(System.nanoTime() + nanos)
    }
}
