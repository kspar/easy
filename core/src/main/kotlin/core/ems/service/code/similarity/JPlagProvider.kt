package core.ems.service.code.similarity

import de.jplag.JPlagComparison
import de.jplag.Language
import de.jplag.Submission
import de.jplag.SubmissionSet
import de.jplag.Token
import de.jplag.TokenType
import de.jplag.comparison.GreedyStringTiling
import de.jplag.comparison.TokenSequenceMapper
import de.jplag.logging.ProgressBar
import de.jplag.logging.ProgressBarLogger
import de.jplag.options.JPlagOptions
import de.jplag.options.LanguageOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Similarity as JPlag measures it: Greedy String Tiling over Python's tokens, so that renaming,
 * reformatting and reordering what does not matter changes nothing.
 *
 * JPlag reads submissions only from files, through native file streams nothing can intercept, and handing
 * it student code that way would mean writing it to disk. So only the *source of tokens* is replaced:
 * [PretokenisedLanguage] is a JPlag language whose `parse()` returns tokens the frontends made in memory.
 * JPlag's own `SubmissionSet` and tiling then run as usual on them, and the [File]s below are only
 * identifiers that are never opened.
 *
 * The pairs are not compared by JPlag's driver - it compares every pair up front, waits up to a day and
 * cannot leave a student's own pairs out - but one tiling at a time, by [SimilarityService].
 */
internal class JPlagProvider : SimilarityProvider {
    private val log = KotlinLogging.logger {}

    override val id = "jplag"

    init {
        // JPlag's default progress bar logs one INFO line per step, and its parsing steps once per
        // submission; replacing the provider is the supported way to turn it off.
        ProgressBarLogger.setProgressBarProvider { _, _ ->
            object : ProgressBar {
                override fun step(number: Int) = Unit
                override fun dispose() = Unit
            }
        }
    }

    /** Feeds JPlag the tokens of each submission, all made in memory before it is given any. */
    private class PretokenisedLanguage(private val tokensByFile: Map<File, List<Token>>) : Language {
        override fun fileExtensions(): List<String> = emptyList()
        override fun getName(): String = "easy-pretokenised"
        override fun getIdentifier(): String = "easy-pretokenised"
        override fun minimumTokenMatch(): Int = MIN_TOKEN_MATCH
        override fun getOptions(): LanguageOptions = LanguageOptions.EMPTY_OPTIONS

        override fun parse(files: Set<File>, normalize: Boolean): List<Token> = tokensByFile.getValue(files.single())
    }

    override fun prepare(sources: List<SimilaritySource>, language: SimilarityLanguage, workers: SimilarityWorkers): PreparedCohort {
        val files = sources.map { File("submission-${it.id}") }
        // Python, unless most of the cohort is not: prose in an exercise that kept `lahendus.py`, which is
        // compared as text instead (see [Tokenised]).
        var frontend: SimilarityFrontend = PythonFrontend
        var results = workers.all(sources.indices.map { i -> { tokenise(sources[i], files[i], frontend, workers) } })
        if (mostlyNotIn(results)) {
            log.info { "Most of this cohort did not parse as ${language.wireName}; comparing it as text instead" }
            frontend = TextFrontend
            results = workers.all(sources.indices.map { i -> { tokenise(sources[i], files[i], frontend, workers) } })
        }
        workers.checkDeadline()

        val compared = if (frontend == TextFrontend) SimilarityLanguage.TEXT else language
        val tokensByFile = HashMap<File, List<Token>>()
        val skipped = mutableListOf<SkippedSubmission>()
        sources.forEachIndexed { i, source ->
            when (val result = results[i]) {
                is Read.Tokens -> tokensByFile[files[i]] = result.tokens
                is Read.Refused -> skipped += SkippedSubmission(source.id, result.reason)
            }
        }
        val usable = sources.indices.filter { files[it] in tokensByFile }
        val usableIds = usable.map { sources[it].id }
        if (usable.size < 2) return PreparedCohort(compared, usableIds, skipped) { _, _ -> error("Fewer than two submissions to compare") }

        // Only what was tokenised, and is long enough to match, goes to JPlag: it has nothing to refuse.
        val jplagLanguage = PretokenisedLanguage(tokensByFile)
        val submissions = usable.map { i -> Submission(sources[i].id.toString(), files[i], true, listOf(files[i]), jplagLanguage) }
        val options = JPlagOptions(jplagLanguage, emptySet(), emptySet())
        val set = SubmissionSet(submissions, null, options)
        // A parse task that died - an OutOfMemoryError, say - leaves its submission unparsed with nothing logged.
        check(set.invalidSubmissions.isEmpty()) { "JPlag refused submissions it was given tokens for: ${set.invalidSubmissions.map { it.name }}" }
        val submissionById = set.submissions.associateBy { it.name.toLong() }
        val gst = GreedyStringTiling(options, TokenSequenceMapper(set))
        return PreparedCohort(compared, usableIds, skipped) { a, b -> pairOf(gst.compare(submissionById.getValue(a), submissionById.getValue(b))) }
    }

    /**
     * One tiling as a pair, oriented as the tiling compared them, the smaller submission first. The maximum
     * similarity is the larger of the two submissions' matched shares, the similarity their combined share.
     */
    private fun pairOf(comparison: JPlagComparison): SimilarityPair {
        val first = comparison.firstSubmission()
        val second = comparison.secondSubmission()
        return SimilarityPair(
            id1 = first.name.toLong(),
            id2 = second.name.toLong(),
            similarity = comparison.similarity(),
            maxSimilarity = comparison.maximalSimilarity(),
            sharedTokens = comparison.matches().sumOf { it.minimumLength() },
            matches = comparison.matches().map { match ->
                SimilarityMatch(
                    lineAt(first.tokenList, match.startOfFirst()),
                    endLineAt(first.tokenList, match.startOfFirst() + match.lengthOfFirst() - 1),
                    lineAt(second.tokenList, match.startOfSecond()),
                    endLineAt(second.tokenList, match.startOfSecond() + match.lengthOfSecond() - 1),
                    match.minimumLength(),
                )
            },
        )
    }

    /**
     * One solution's tokens, or why there are none, on a worker thread. The deadline is read before each,
     * so once the budget is spent the rest cost nothing.
     */
    private fun tokenise(source: SimilaritySource, id: File, frontend: SimilarityFrontend, workers: SimilarityWorkers): Read {
        workers.checkDeadline()
        val tokenised = try {
            frontend.read(id, source.solution, MAX_SOLUTION_TOKENS)
        } catch (_: SolutionTooLarge) {
            return Read.Refused("TOO_LARGE", recovered = false)
        } catch (e: SolutionTooComplex) {
            log.info { "Submission ${source.id} would take too long to tokenise (${e.message}); skipping it" }
            return Read.Refused("TOO_COMPLEX", recovered = false)
        } catch (_: StackOverflowError) {
            // One submission's problem, on its own thread; at these limits nothing that fits overflows.
            log.warn { "Submission ${source.id} overflowed the parser stack" }
            return Read.Refused("CANNOT_PARSE", recovered = true)
        } catch (e: Exception) {
            log.debug(e) { "Submission ${source.id} could not be tokenised" }
            return Read.Refused("CANNOT_PARSE", recovered = true)
        }

        // Fewer tokens than the shortest match can never match; JPlag's own TOO_SMALL counts FILE_END,
        // so one token short would be valid and unmatchable.
        if (tokenised.tokens.count { !it.type.isExcludedFromMatching() } < MIN_TOKEN_MATCH) {
            return Read.Refused("TOO_SMALL", tokenised.recovered)
        }
        if (repetition(tokenised.tokens) > MAX_REPETITION) {
            return Read.Refused("TOO_REPETITIVE", recovered = false)
        }
        return Read.Tokens(tokenised.tokens, tokenised.recovered)
    }

    /** What reading one solution gave; [recovered] says whether it read as the language at all. */
    private sealed interface Read {
        val recovered: Boolean

        class Tokens(val tokens: List<Token>, override val recovered: Boolean) : Read

        /** Not compared, for [reason]. */
        class Refused(val reason: String, override val recovered: Boolean) : Read
    }

    /**
     * Whether most of a cohort is not in its language: its parser had to recover to read a solution, or
     * could not read it. Solutions refused on size or cost say nothing about their language; a too-small one
     * still does - one-sentence answers read as Python are both.
     */
    private fun mostlyNotIn(results: List<Read>): Boolean {
        val telling = results.filter { it is Read.Tokens || (it as Read.Refused).reason in TELLING_REFUSALS }
        return telling.count { it.recovered } * 2 > telling.size
    }

    /**
     * Σ c² over the count c of each distinct run of [MIN_TOKEN_MATCH] consecutive token types: what bounds
     * the cost of one pair. The tiling cannot be interrupted once started, and for every run in one solution
     * it visits every place the same run starts in the other, so repetition is what makes a pair slow - two
     * solutions of differently repeated statements took minutes for their one pair. An ordinary solution
     * scores about its length; one statement written out a thousand times, about a million.
     */
    private fun repetition(tokens: List<Token>): Long {
        val types: List<TokenType> = tokens.map { it.type }.filterNot { it.isExcludedFromMatching() }
        val counts = HashMap<List<TokenType>, Int>()
        for (start in 0..types.size - MIN_TOKEN_MATCH) counts.merge(types.subList(start, start + MIN_TOKEN_MATCH), 1, Int::plus)
        return counts.values.sumOf { it.toLong() * it }
    }

    /**
     * A match indexes tokens, and a reader needs lines. Out of range is 0, "no line", rather than the last
     * token, which is FILE_END with line -1.
     */
    private fun lineAt(tokens: List<Token>, index: Int): Int =
        tokens.getOrNull(index)?.startLine?.takeIf { it > 0 } ?: 0

    /** The line a match's last token ends on: a multi-line string ends a statement on its last line. */
    private fun endLineAt(tokens: List<Token>, index: Int): Int =
        tokens.getOrNull(index)?.let { maxOf(it.endLine, it.startLine) }?.takeIf { it > 0 } ?: 0

    private companion object {
        /**
         * The shortest run of tokens that counts as a match: 6, where JPlag's default is 12. An exercise like
         * "read two numbers and print the sum" is about ten tokens end to end, so at 12 nothing ever matched.
         * Calibrated; doc/jplag-integration.md has the sweep.
         */
        const val MIN_TOKEN_MATCH = 6

        /** The most lexical tokens one solution may have, about 35 KB of code: parsing is where a solution's cost is. */
        const val MAX_SOLUTION_TOKENS = 10_000

        /** The most repetition one solution may have; see [repetition]. The worst pair it admits takes under a second. */
        const val MAX_REPETITION = 1_000_000L

        /** Refusals that still say whether a solution is in the language: see [mostlyNotIn]. */
        val TELLING_REFUSALS = setOf("TOO_SMALL", "CANNOT_PARSE")
    }
}
