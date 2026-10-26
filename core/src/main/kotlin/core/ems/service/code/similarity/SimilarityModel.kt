package core.ems.service.code.similarity

/**
 * One student's solution, straight out of the `submission.solution` text column.
 *
 * [owner] identifies whoever wrote it — a student id in production. Two sources with the same
 * non-null owner are never compared against each other: a student's own attempts are not plagiarism,
 * and left in they are a way for a student to bury the evidence against themselves. Fifteen
 * resubmissions produce 105 self-pairs at 100%, which is more than the top-N cap returns, so every
 * genuine pair is pushed out of the response entirely. Null means "unknown owner", which compares
 * against everything — the right default for a caller that has no such notion.
 */
data class SimilaritySource(val id: Long, val solution: String, val owner: String? = null)

/** A matched region, as line ranges in each submission — token indices are no use to a reader. */
data class SimilarityMatch(
    val startLine1: Int, val endLine1: Int,
    val startLine2: Int, val endLine2: Int,
    val tokens: Int,
)

/**
 * Two submissions and how much of them matched, as JPlag measures it, in two ways that answer
 * different questions - which is why both are reported.
 *
 * [similarity] is the matched share of both submissions together: matched tokens on each side,
 * summed, over both lengths summed. It is symmetric, and high only when the two are largely the same.
 * [maxSimilarity] is the matched share of whichever submission is covered more - in practice the
 * shorter one. A short solution pasted whole into a long one scores near 100% there and low on
 * [similarity]; that pair is the one ranking on [similarity] alone would bury, which is why the page's
 * threshold goes by [maxSimilarity], and the ranking by it after how much the two share (see
 * PairRanking). Read together: both high, the two are much the same; only the maximum high, one is
 * contained in the other.
 */
data class SimilarityPair(
    val id1: Long, val id2: Long,
    val similarity: Double, val maxSimilarity: Double,
    val matches: List<SimilarityMatch>,
    /** What the provider measured, by name; see [ProviderSession.metrics]. */
    val metrics: List<SimilarityMetric> = emptyList(),
)

/** One named measurement of a pair: a share from 0 to 1, or a count. */
data class SimilarityMetric(val id: String, val value: Double, val unit: MetricUnit)

enum class MetricUnit(val wireName: String) { SHARE("percent"), COUNT("count") }

data class SkippedSubmission(val id: Long, val reason: String)

/**
 * Which provider compares a cohort, and in which language: [language] is the exercise's, or text when no
 * provider that may run takes the exercise's language - and then [fallback] says so.
 */
data class SimilarityBinding(
    val provider: String,
    val language: SimilarityLanguage,
    val fallback: String? = null,
    /**
     * Whether the course asked for the optimised mode. Carried here, with the provider it applies to, so
     * the estimate and the run read one value: passed beside the binding, the run could be left without it
     * while the estimate, admitting the cohort as optimised, had it.
     */
    val optimised: Boolean = false,
    /**
     * Whether the provider may compare the cohort as text if it finds that it is not in [language] - JPlag
     * does, for an essay exercise that kept `lahendus.py`. Off where an admin has switched text off, for the
     * provider or as a whole: decided while running, that fallback compared word by word where the admin
     * had said no, and said nothing of it.
     */
    val textAllowed: Boolean = true,
)

data class SimilarityOutcome(
    val pairs: List<SimilarityPair>,
    val skipped: List<SkippedSubmission>,
    /** The language that was actually compared, which is not the one asked for when that one cannot run here. */
    val language: SimilarityLanguage,
    val truncated: Boolean,
    /**
     * Submissions that are identical - the same text but for layout - by three or more authors, each
     * group's ids in id order, the lowest first. A group was compared as one, so [pairs] holds none between its members, and at
     * most one between the group and any other submission, naming one member for them all.
     */
    val groups: List<List<Long>> = emptyList(),
    /** The provider that measured it; see [SimilarityProvider.id]. */
    val provider: String = "",
    /** Pairs worth comparing - one per pair of comparands, a student's own left out - and how many were scored. */
    val pairsTotal: Int = 0,
    val pairsCompared: Int = 0,
    /**
     * False when an optimised run stopped at its deadline with pairs left, and returned the best of what
     * it had scored, the likeliest first, rather than nothing.
     */
    val complete: Boolean = true,
)

/**
 * The rows a comparison ran on, and what it found.
 *
 * Both come back together on purpose. [SimilarityService.compare] takes a *loader* so the rows are
 * fetched under the permit rather than before it, which left the caller needing a value produced
 * inside a lambda — and the first version of that smuggled it out through a captured `var`. That
 * worked only while the loader happened to run exactly once, synchronously, on the caller's thread
 * before every return; nothing in the signature said so. Any later early exit would have handed the
 * controller an empty row list beside a non-empty `pairs`, and the page joins the two by id, so the
 * teacher would have seen pairs with no names and no code and nothing would have failed. Returning
 * them as one value makes "you have the rows iff the comparison ran" a property of the type.
 */
data class SimilarityRun<T>(val rows: List<T>, val outcome: SimilarityOutcome)

/**
 * A submission as the estimate sees it: its size in UTF-8 bytes as every provider counts it, its author, and a fingerprint of its
 * text - never the text, so a cohort can be priced without loading it. Equal fingerprints are compared
 * once, as identical texts are; null is a text of its own.
 */
data class SizedSource(val id: Long, val bytes: Int, val owner: String? = null, val digest: String? = null)

/**
 * What a comparison would take: how many pairs it would score, and how long on this server's pool,
 * against the budget it would run under. See [SimilarityService.estimate].
 */
data class SimilarityEstimate(
    val submissions: Int,
    val pairs: Long,
    val threads: Int,
    val wallNanos: Long,
    val budgetSec: Long,
    /**
     * Whether it would run optimised: then [wallNanos] is the most it can take - pairs the provider can
     * rule out are not scored - and over the budget it stops with the likeliest pairs found, so it is not
     * refused for being over.
     */
    val optimised: Boolean = false,
) {
    val withinBudget: Boolean get() = wallNanos <= budgetSec * 1_000_000_000L

    /** Whole seconds, rounded up: "about 0 s" is not something to tell a teacher about a real run. */
    val seconds: Long get() = (wallNanos + 999_999_999L) / 1_000_000_000L
}

/** Refused before the permit: the estimate is over the budget, so the run would only time out. */
class SimilarityTooCostly(val estimate: SimilarityEstimate) :
    RuntimeException("Similarity comparison would take about ${estimate.seconds}s, over the ${estimate.budgetSec}s budget")

class TooManySubmissions(val requested: Int, val limit: Int) :
    RuntimeException("Similarity requested for $requested submissions, limit is $limit")

/** No provider an admin has left on can compare [language], nor text in its place - or the language is switched off. */
class SimilarityUnavailable(val language: SimilarityLanguage) :
    RuntimeException("No similarity provider may compare ${language.wireName}")

class SimilarityBusy : RuntimeException("Another similarity comparison is already running")

class SimilarityTimedOut(val budgetSec: Long) :
    RuntimeException("Similarity comparison exceeded its ${budgetSec}s budget")

/** [text]'s length in UTF-8 bytes, Postgres's `octet_length`, without encoding it: what every provider's sizes are in. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    for (c in text) bytes += when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        // A pair of surrogates is one character of four bytes.
        Character.isSurrogate(c) -> 2
        else -> 3
    }
    return bytes
}
