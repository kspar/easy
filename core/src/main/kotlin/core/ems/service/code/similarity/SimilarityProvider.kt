package core.ems.service.code.similarity

/**
 * One way of measuring how alike a cohort's submissions are: a similarity *provider*.
 *
 * The split is the one automated assessment already has. There, an exercise names what to check and a
 * grader image does the checking, and core neither knows nor cares how; here, [SimilarityService] owns
 * everything a comparison is regardless of how it is measured - the one-at-a-time permit, the time
 * budget, the worker pool, who may not be paired with whom, identical submissions compared once, which
 * pairs are kept and in what order - and a provider owns only the measuring. JPlag's token tiling is
 * one ([JPlagProvider]); a plain text distance, or a service outside core, would be others, with the
 * engine unchanged.
 *
 * A provider is asked for one cohort at a time through a [ProviderSession], which the engine opens on
 * the comparison's own workers and deadline, and closes when the comparison ends however it ends.
 */
internal interface SimilarityProvider {
    /** A stable name for what measured a comparison: recorded with it, and the key a choice of provider is made by. */
    val id: String

    /**
     * The languages it can compare, and how ready each is. A language that is not here is never bound
     * to it; an [LanguageSupport.EXPERIMENTAL] one only once an admin switches it on, so that a new
     * language - or one whose tokeniser has a history - does not go live on deploy.
     */
    val languages: Map<SimilarityLanguage, LanguageSupport>

    /** Its place in the preference order until an admin gives it another: higher is preferred. */
    val defaultPriority: Int

    /**
     * Whether [ProviderSession.details] says where two submissions match. When it does, no matches is an
     * answer - the pair shares nothing - and the page paints exactly what it is told; when it does not,
     * no matches says nothing, and the page has to work out what the two have in common itself. Without
     * this the page could not tell the two apart: edit distance at 95% and JPlag at 0% both send none.
     */
    val matchRanges: Boolean

    /**
     * The language this provider compares [language] as in this JVM: [language] itself, or the fallback
     * it uses when it cannot read that language here. Asked before the permit is taken, so a comparison
     * that cannot run as asked is known not to before anybody waits for it.
     */
    fun comparesAs(language: SimilarityLanguage): SimilarityLanguage

    /**
     * What one solution of [chars] characters costs before any pair, in nanoseconds of one worker; and
     * [pairNanos], what one pair costs. Measured on this provider, not derived, and only as exact as a
     * measurement: they are what [SimilarityService.estimate] adds up to refuse a cohort that would not
     * finish inside the budget before anybody waits for it, and to tell a teacher how long one will take.
     * Generous rather than tight - an overestimate refuses a run that might have finished, but an
     * underestimate is still bounded by the deadline.
     */
    fun prepareNanos(chars: Int): Long

    fun pairNanos(charsA: Int, charsB: Int): Long

    /**
     * Whether its sessions give a [ProviderSession.bound] for every pair: what lets the optimised mode
     * rule pairs out, and compare the likeliest first. Known before any text is read, for the estimate.
     */
    val bounds: Boolean get() = false

    /** Whether a solution of [chars] characters is compared at all, rather than skipped by size. */
    fun compares(chars: Int): Boolean = chars > 0

    fun open(request: ProviderRequest, run: RunContext): ProviderSession
}

/** Whether a language is ready for every course, or used only where an admin has switched it on. */
internal enum class LanguageSupport(val wireName: String) { STABLE("stable"), EXPERIMENTAL("experimental") }

/** What a provider is asked to compare. [template] is starter code every submission was built on, if there is any. */
internal class ProviderRequest(
    val language: SimilarityLanguage,
    val sources: List<SimilaritySource>,
    val template: String?,
)

/**
 * The comparison a session runs inside: its pool, its deadline, and the budget the deadline came from,
 * for the refusal to name. A session reads the deadline wherever it can stop, and runs its parallel
 * work on [workers] rather than threads of its own, so that the permit bounds all of it.
 */
internal class RunContext(val workers: SimilarityWorkers, val deadline: Deadline, val budgetSec: Long)

/**
 * One cohort, from preparing it to the last pair's details.
 *
 * The engine calls [prepare] once, then [ready] once with the submissions it will compare, then
 * [score] for the pairs worth comparing - from several workers at once - and [details] for the few it
 * returns. Ids are [SimilaritySource.id]s throughout.
 */
internal interface ProviderSession : AutoCloseable {

    /** Reads the cohort, refusing what cannot be compared. Runs before any pair; may use the workers. */
    fun prepare(): PreparedCohort

    /**
     * Called once, after grouping, with the submissions that will be compared - one per group of
     * identical ones - before the first pair. For work per submission that must not race the pairs.
     */
    fun ready(representatives: List<Long>)

    /**
     * How alike [a] and [b] are, or null if this provider has nothing to say about the pair. Called from
     * several workers at once, so it must be thread-safe; the engine reads the deadline before each call.
     */
    fun score(a: Long, b: Long): PairScore?

    /**
     * Upper bounds on what [score] would answer for [a] and [b], computed without scoring them - or null
     * for a provider that has none ([SimilarityProvider.bounds] false). Never below the real values, for
     * a pair ruled out by them is never scored. Thread-safe, like [score].
     */
    fun bound(a: Long, b: Long): PairBound? = null

    /**
     * Where [pair]'s two sides match, as line ranges, read from [shownFirst] and [shownSecond]: the
     * submissions the outcome names for [PairScore.first] and [PairScore.second], which for a group can
     * be another member than the one compared. Called only for pairs that are returned.
     */
    fun details(pair: PairScore, shownFirst: Long, shownSecond: Long): List<SimilarityMatch>

    /**
     * The numbers this provider measured for [pair], named, for the page to show beside the score: JPlag's
     * two similarities and its shared tokens, edit distance's Levenshtein ratio and Dice coefficient.
     * Called only for pairs that are returned, so measuring them costs nothing per pair compared.
     */
    fun metrics(pair: PairScore): List<SimilarityMetric>

    override fun close() = Unit
}

/**
 * A prepared cohort: what the provider can compare, and what it refused.
 *
 * [usable] is in the order pairs are to be oriented and groups placed in - the cohort's own, less what
 * was refused. Identical submissions are found through [groupKey], equal for any two that are
 * identical and rarely otherwise, and confirmed with [identical]: two submissions it calls identical
 * must score alike against everything, since a group is compared once for all its members.
 */
internal class PreparedCohort(
    /** The language actually compared, which may be a fallback the provider chose; see [SimilarityProvider.comparesAs]. */
    val language: SimilarityLanguage,
    val usable: List<Long>,
    val skipped: List<SkippedSubmission>,
    val groupKey: (Long) -> Long,
    val identical: (Long, Long) -> Boolean,
)

/**
 * How alike one pair is, in the three numbers the engine ranks by.
 *
 * [score] is a share from 0 to 1 - the one a teacher's threshold filters on, and the one a pair is
 * ranked by when the highest-scoring are kept. [rank] is how much the two share, in the provider's own
 * unit, and what the outcome is ordered by first. [similarity] is the symmetric share both sides have
 * in common, reported beside [score] and breaking ties in both orders.
 *
 * [first] and [second] are the pair as the provider oriented it - for JPlag the smaller submission
 * first, as its tiling compares them - so that [details] reads each side's lines from the right one.
 * [handle] is the provider's own record of the comparison, kept only for pairs that are returned, so
 * that their details need not be computed again.
 */
/** At least as high as a pair's [PairScore.score], [PairScore.rank] and [PairScore.similarity]. */
internal class PairBound(val score: Double, val rank: Double, val similarity: Double)

internal class PairScore(
    val first: Long,
    val second: Long,
    val score: Double,
    val rank: Double,
    val similarity: Double,
    val handle: Any?,
)
