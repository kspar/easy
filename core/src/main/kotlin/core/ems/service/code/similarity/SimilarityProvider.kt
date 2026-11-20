package core.ems.service.code.similarity

/**
 * One way of measuring how alike two submissions are: [JPlagProvider] tiles tokens, [EditDistanceProvider]
 * compares characters. [SimilarityService] owns everything else - the permit, the time budget, the
 * workers, who is never paired with whom, identical submissions, which pairs are kept.
 */
internal interface SimilarityProvider {
    /** The name the response gives what measured it. */
    val id: String

    /** Reads the cohort, on [workers] and within their deadline, ready for its pairs to be scored. */
    fun prepare(sources: List<SimilaritySource>, language: SimilarityLanguage, workers: SimilarityWorkers): PreparedCohort
}

/**
 * What a provider can compare - [usable], in the cohort's order - what it skipped, and the language it read.
 * [score] compares two of [usable], from several workers at once, so it must be thread-safe; it is called
 * only when there are two or more.
 */
internal class PreparedCohort(
    val language: SimilarityLanguage,
    val usable: List<Long>,
    val skipped: List<SkippedSubmission>,
    val score: (Long, Long) -> SimilarityPair,
)
