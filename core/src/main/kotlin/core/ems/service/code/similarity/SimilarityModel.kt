package core.ems.service.code.similarity

/**
 * One student's solution. Two sources with the same [owner] are never paired: a student's own attempts are
 * not plagiarism, and fifteen resubmissions would otherwise fill the returned pairs with self-pairs at 100%.
 */
data class SimilaritySource(val id: Long, val solution: String, val owner: String)

/** A matched region, as line ranges in each of the two submissions. */
data class SimilarityMatch(
    val startLine1: Int, val endLine1: Int,
    val startLine2: Int, val endLine2: Int,
    val tokens: Int,
)

/**
 * Two submissions and how alike they are, as shares from 0 to 1.
 *
 * [maxSimilarity] is the share of whichever submission is covered more: a short solution pasted whole into
 * a long one is near 1 there and low on [similarity], the share of both together. The page's threshold
 * filters on [maxSimilarity]. Edit distance measures one number, so it is both.
 *
 * [sharedTokens] is how much code the two share, as JPlag counts it, and [matches] where; null and empty
 * for edit distance.
 */
data class SimilarityPair(
    val id1: Long, val id2: Long,
    val similarity: Double, val maxSimilarity: Double,
    val sharedTokens: Int?,
    val matches: List<SimilarityMatch>,
)

data class SkippedSubmission(val id: Long, val reason: String)

data class SimilarityOutcome(
    /** The provider that measured it: `jplag` or `edit-distance`. */
    val provider: String,
    /** The language compared, which is text for a Python cohort that turned out to be prose. */
    val language: SimilarityLanguage,
    val pairs: List<SimilarityPair>,
    val skipped: List<SkippedSubmission>,
    /**
     * Identical submissions by three or more authors, each group's ids in id order. A group is compared
     * once, so [pairs] has no pair inside it and at most one between it and any other submission.
     */
    val groups: List<List<Long>>,
    /** Whether more pairs scored than were returned. */
    val truncated: Boolean,
)

/** A running check, for the teacher waiting on it: preparing, or [comparing] - [done] of [total] pairs. */
data class SimilarityProgress(val comparing: Boolean, val done: Long, val total: Long, val elapsedSec: Long, val budgetSec: Long)

/** Thrown where a check reads its deadline once the time is up; [SimilarityService] answers it by name. */
internal class SimilarityTimedOut : RuntimeException("Similarity comparison ran out of time")
