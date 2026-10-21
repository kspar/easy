package core.ems.service.code.similarity

/**
 * Similarity as the check measured it before JPlag: whole texts, character by character.
 *
 * Two numbers per pair, as the old page showed them: the Levenshtein-style [EditDistance.ratio] and the
 * bigram [EditDistance.dice]. Neither knows what code is, so renaming every variable lowers both, and
 * both work on any text in any language - which is what makes this provider the one that can always
 * answer. The score a threshold filters on is the higher of the two, and the outcome is ordered by their
 * mean, as the old check ordered by their sum.
 *
 * What it does not do, it says: it has no match ranges, so [details] is empty and the page shows the
 * difference itself.
 *
 * [maxSolutionChars] bounds what one pair can cost. The ratio is |a|·|b|/64 word steps, so two
 * solutions at the bound cost about six million - milliseconds - and one above it is skipped as
 * TOO_LARGE rather than holding the permit for longer.
 */
internal class EditDistanceProvider(
    private val maxSolutionChars: Int = DEFAULT_MAX_SOLUTION_CHARS,
) : SimilarityProvider {

    override val id = ID

    /** Characters are characters in every language. */
    override val languages = SimilarityLanguage.entries.associateWith { LanguageSupport.STABLE }

    /** First by default: it is what similarity measured before there were providers. */
    override val defaultPriority = 100

    /** A ratio over whole texts says how alike they are, not where. */
    override val matchRanges = false

    /** Its bigrams, sorted: measured at about 100 ns a character. */
    override fun prepareNanos(chars: Int): Long = 100L * chars

    /**
     * The bit-parallel LCS walks the longer text once per 64 characters of the shorter, measured at
     * about 7 ns a step on one core; Dice merges two sorted bigram lists. 500 solutions of 2.7 KB come
     * to 104 s here, against 100 s measured.
     */
    override fun pairNanos(charsA: Int, charsB: Int): Long =
        7L * ((minOf(charsA, charsB) + 63) / 64) * maxOf(charsA, charsB) + 2L * (charsA + charsB) + 2_000

    /** Blank solutions are too small and those above the cap too large; neither is compared. */
    override fun compares(chars: Int): Boolean = chars in 1..maxSolutionChars

    /** Characters are characters in every language, so every language is compared as itself. */
    override fun comparesAs(language: SimilarityLanguage): SimilarityLanguage = language

    override fun open(request: ProviderRequest, run: RunContext): ProviderSession = Session(request, run)

    private inner class Session(private val request: ProviderRequest, private val run: RunContext) : ProviderSession {
        private val solutionById = request.sources.associate { it.id to it.solution }
        private lateinit var bigramsById: Map<Long, LongArray>

        override fun prepare(): PreparedCohort {
            if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)
            val skipped = mutableListOf<SkippedSubmission>()
            val usable = mutableListOf<Long>()
            for (source in request.sources) {
                when {
                    // Nothing to compare: blank answers would pair with each other as identical.
                    source.solution.isBlank() -> skipped += SkippedSubmission(source.id, "TOO_SMALL")
                    source.solution.length > maxSolutionChars -> skipped += SkippedSubmission(source.id, "TOO_LARGE")
                    else -> usable += source.id
                }
            }
            bigramsById = run.workers.all(usable.map { id -> { id to EditDistance.bigrams(solutionById.getValue(id)) } }).toMap()
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
            val mean = (levenshtein + dice) / 2
            // Both measures are symmetric, so the pair is oriented by id: the same whichever way it is asked.
            return PairScore(minOf(a, b), maxOf(a, b), score = maxOf(levenshtein, dice), rank = mean, similarity = mean, handle = doubleArrayOf(levenshtein, dice))
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

        /** About 20,000 characters: some 600 lines, well past an ordinary solution. */
        const val DEFAULT_MAX_SOLUTION_CHARS = 20_000
    }
}
