package core.ems.service.code.similarity

import java.util.concurrent.ConcurrentHashMap

/**
 * Similarity as the check measured it before JPlag: whole texts, character by character.
 *
 * Two numbers per pair, as the old page showed them: the Levenshtein-style [EditDistance.ratio] and the
 * bigram [EditDistance.dice]. Neither knows what code is, so renaming every variable lowers both, and
 * both work on any text in any language - which is what makes this provider the one that can always
 * answer.
 *
 * The ratio is the score, the rank and the similarity; Dice is measured and shown, but decides nothing.
 * Dice over bigram *sets* is high for any two programs in one language - they share their keywords,
 * operators and indentation - and the score used to be the higher of the two, as the old page filtered on:
 * the stress test of EZ-1667 found pairs scored 80% whose ratio was 0%, so the highest-scoring list and the
 * page's threshold were mostly that noise.
 *
 * What the ratio's bound buys the optimised mode is the order - likeliest first, so a run stopped at its
 * budget has the best pairs - not pruning: unrelated solutions in one language are 60-70% alike character
 * by character, so for a cohort of like lengths the bound sits about where the hundredth best pair does
 * (see [EditDistance.lcsBound]). The stress test's 250 Python solutions compared 26,126 of 26,565 pairs,
 * scored by the ratio as by the higher of the two before it; an edit-distance cohort costs about what a full
 * run does.
 *
 * What it does not do, it says: it has no match ranges, so [details] is empty and the page shows the
 * difference itself.
 *
 * [maxSolutionBytes] bounds what one pair can cost. The ratio is |a|·|b|/64 word steps, so two
 * solutions at the bound cost about six million - milliseconds - and one above it is skipped as
 * TOO_LARGE rather than holding the permit for longer.
 */
internal class EditDistanceProvider(
    private val maxSolutionBytes: Int = DEFAULT_MAX_SOLUTION_BYTES,
) : SimilarityProvider {

    override val id = ID

    /** Characters are characters in every language. */
    override val languages = SimilarityLanguage.entries.associateWith { LanguageSupport.STABLE }

    /** First by default: it is what similarity measured before there were providers. */
    override val defaultPriority = 100

    /** A ratio over whole texts says how alike they are, not where. */
    override val matchRanges = false

    /** The ratio from above, linear in the texts' lengths: see [Session.bound]. */
    override val bounds = true

    /** Its bigrams, sorted: measured at about 100 ns a character. */
    override fun prepareNanos(bytes: Int): Long = 100L * bytes

    /**
     * The bit-parallel LCS walks the longer text once per 64 characters of the shorter, measured at
     * about 7 ns a step on one core; Dice merges two sorted bigram lists. 500 solutions of 2.7 KB come
     * to 104 s here, against 100 s measured.
     */
    override fun pairNanos(bytesA: Int, bytesB: Int): Long =
        7L * ((minOf(bytesA, bytesB) + 63) / 64) * maxOf(bytesA, bytesB) + 2L * (bytesA + bytesB) + 2_000

    /** Blank solutions are too small and those above the cap, in UTF-8 bytes, too large; neither is compared. */
    override fun compares(bytes: Int): Boolean = bytes in 1..maxSolutionBytes

    /** Characters are characters in every language, so every language is compared as itself. */
    override fun comparesAs(language: SimilarityLanguage): SimilarityLanguage = language

    override fun open(request: ProviderRequest, run: RunContext): ProviderSession = Session(request, run)

    private inner class Session(private val request: ProviderRequest, private val run: RunContext) : ProviderSession {
        private val solutionById = request.sources.associate { it.id to it.solution }
        private lateinit var bigramsById: Map<Long, LongArray>

        /**
         * Built on a pair's first [bound], not in [prepare]: only the optimised mode reads them, and at the
         * cap they are some 60 MB - two bytes a character and four a bigram - that a full run held for nothing.
         */
        private val profileById = ConcurrentHashMap<Long, EditDistance.Profile>()

        private fun profile(id: Long) = profileById.computeIfAbsent(id) { EditDistance.profile(solutionById.getValue(it)) }

        /** Its quick bound is [bound] itself: ordering every pair by the exact one costs about what Dice did. */
        override val quickBoundIsExact = true

        override fun prepare(): PreparedCohort {
            if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)
            val skipped = mutableListOf<SkippedSubmission>()
            val usable = mutableListOf<Long>()
            for (source in request.sources) {
                when {
                    // Nothing to compare: blank answers would pair with each other as identical.
                    source.solution.isBlank() -> skipped += SkippedSubmission(source.id, "TOO_SMALL")
                    // In UTF-8 bytes, as the estimate reads them from the database, so it skips what this
                    // skips; counted differently, the two disagreed and the estimate priced pairs this never ran.
                    utf8Length(source.solution) > maxSolutionBytes ->
                        skipped += SkippedSubmission(source.id, "TOO_LARGE")
                    else -> usable += source.id
                }
            }
            bigramsById = run.workers.all(
                usable.map { id ->
                    {
                        val solution = solutionById.getValue(id)
                        id to run.preparing(request.language, prepareNanos(utf8Length(solution))) { EditDistance.bigrams(solution) }
                    }
                },
            ).toMap()
            if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)
            return PreparedCohort(
                language = request.language,
                usable = usable,
                skipped = skipped,
                groupKey = { solutionById.getValue(it).hashCode().toLong() },
                // Exactly the same text: layout counts in both measures, so two layouts of one program do
                // not score alike against a third, and only what does may be compared once for both.
                identical = { a, b -> solutionById.getValue(a) == solutionById.getValue(b) },
            )
        }

        override fun ready(representatives: List<Long>) = Unit

        override fun score(a: Long, b: Long): PairScore {
            val levenshtein = EditDistance.ratio(solutionById.getValue(a), solutionById.getValue(b)) {
                if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)
            }
            val dice = EditDistance.dice(bigramsById.getValue(a), bigramsById.getValue(b))
            // Both measures are symmetric, so the pair is oriented by id: the same whichever way it is asked.
            return PairScore(
                minOf(a, b), maxOf(a, b), score = levenshtein, rank = levenshtein, similarity = levenshtein,
                handle = doubleArrayOf(levenshtein, dice),
            )
        }

        /**
         * The ratio's [EditDistance.ratioBound], from the characters and the bigrams the two share: about the
         * texts, not only their sizes - a bound from lengths alone was about 1 for every pair of like length,
         * the usual cohort, and ordered a run's pairs by size - and two merges of sorted lists, a small part
         * of what the ratio costs. The ratio is all three numbers, so it bounds all three.
         */
        override fun bound(a: Long, b: Long): PairBound {
            val levenshtein = EditDistance.ratioBound(profile(a), profile(b))
            return PairBound(score = levenshtein, rank = levenshtein, similarity = levenshtein)
        }

        override fun details(pair: PairScore, shownFirst: Long, shownSecond: Long): List<SimilarityMatch> = emptyList()

        override fun metrics(pair: PairScore): List<SimilarityMetric> {
            val (levenshtein, dice) = pair.handle as DoubleArray
            return listOf(
                SimilarityMetric("levenshtein", levenshtein, MetricUnit.SHARE),
                SimilarityMetric("dice", dice, MetricUnit.SHARE),
            )
        }
    }

    companion object {
        const val ID = "edit-distance"

        /** About 20,000 characters - bytes, counted as the database does - some 600 lines, well past an ordinary solution. */
        const val DEFAULT_MAX_SOLUTION_BYTES = 20_000
    }
}
