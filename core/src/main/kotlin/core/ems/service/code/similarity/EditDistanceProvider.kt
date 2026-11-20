package core.ems.service.code.similarity

/**
 * Similarity of whole texts, character by character ([EditDistance.ratio]). Renaming every variable lowers
 * the score, but it works on any text, which is why SQL and prose use it.
 *
 * It has no match ranges: its pairs have no matches, and the page works out the shared text itself.
 */
internal class EditDistanceProvider : SimilarityProvider {

    override val id = "edit-distance"

    override fun prepare(sources: List<SimilaritySource>, language: SimilarityLanguage, workers: SimilarityWorkers): PreparedCohort {
        val skipped = mutableListOf<SkippedSubmission>()
        val usable = mutableListOf<Long>()
        for (source in sources) {
            when {
                // Blank answers would pair with each other as identical.
                source.solution.isBlank() -> skipped += SkippedSubmission(source.id, "TOO_SMALL")
                source.solution.length > MAX_SOLUTION_CHARS -> skipped += SkippedSubmission(source.id, "TOO_LARGE")
                else -> usable += source.id
            }
        }
        val solutionById = sources.associate { it.id to it.solution }
        return PreparedCohort(language, usable, skipped) { a, b ->
            val ratio = EditDistance.ratio(solutionById.getValue(a), solutionById.getValue(b))
            // Symmetric, so oriented by id: the same whichever way round it is asked.
            SimilarityPair(minOf(a, b), maxOf(a, b), ratio, ratio, sharedTokens = null, matches = emptyList())
        }
    }

    private companion object {
        /**
         * About 600 lines, well past an ordinary solution. A pair costs |a|·|b|/64 word steps - milliseconds
         * at this size - and a longer solution is skipped as TOO_LARGE rather than hold the permit for longer.
         */
        const val MAX_SOLUTION_CHARS = 20_000
    }
}
