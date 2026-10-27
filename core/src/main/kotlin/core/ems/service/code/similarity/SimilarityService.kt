package core.ems.service.code.similarity

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Similarity, entirely in memory: which submissions of a cohort are most alike.
 *
 * The pieces, each in a file of its own:
 *
 *  - a [SimilarityProvider] measures how alike two submissions are. That is the only part that depends
 *    on how similarity is measured. [JPlagProvider] tiles tokens, with one [SimilarityFrontend] per
 *    language, and [EditDistanceProvider] compares characters; [SimilarityRegistry] chooses between them;
 *  - [SimilarityWorkers] runs a comparison's work in parallel, and holds every rule about doing so;
 *  - [PairRanking] compares the pairs and keeps the best;
 *  - this class admits one comparison at a time, bounds what each can cost, groups identical
 *    submissions, and assembles the outcome - the same for every provider.
 */
@Service
class SimilarityService(
    @param:Value("\${easy.core.similarity.max-submissions}") private val maxSubmissions: Int,
    /**
     * Largest solution this will tokenise, in lexical tokens - names, literals and symbols, not comments
     * or whitespace, as each frontend counts them before anything costlier runs. Above it the
     * submission is skipped as TOO_LARGE.
     *
     * [maxSubmissions] bounds the wrong quantity on its own. Parsing is where a submission's cost
     * is, and it is not even linear in the pathological case: a single 250 KB Python submission
     * consisting of one enormous expression once took **156 seconds**, and 50,000 chained method calls
     * in Java ten minutes. That is a student holding the one global permit, uninterruptibly. At this
     * cap the worst single inputs measured cost a second or two; what length cannot bound - deep
     * nesting, which is costly in a few hundred characters - each frontend bounds by time of its own,
     * skipping the submission as TOO_COMPLEX.
     *
     * Lexical tokens rather than the characters this used to count, because comments and blank lines
     * never reach a parser: under a character cap a copy padded with one long comment was skipped as
     * TOO_LARGE - a way out of the comparison for the price of one line, with code that still ran.
     */
    @param:Value("\${easy.core.similarity.max-solution-tokens}") private val maxSolutionTokens: Int,
    /**
     * How repetitive a solution may be and still be compared - the bound on what one pair can cost.
     *
     * Measured as Σ c² over the count c of each distinct run of the language's minimum match length of
     * consecutive token types (see [JPlagProvider]). A solution that repeats no run scores about its own
     * length; one statement written out a thousand times in a row scores about a million. Above the
     * limit the submission is skipped as TOO_REPETITIVE, and the page names it.
     *
     * Greedy String Tiling cannot be interrupted once a pair is started, and repetition is what makes
     * a pair expensive: for every run in one solution it visits every position in the other where the
     * same run starts, and does so once per tiling pass. One pass over solutions scoring A and B is
     * therefore at most √(A·B) visits (Cauchy-Schwarz over the runs they share), and passes repeat at
     * most once per distinct match length - about √(2n). Bounding every solution's score bounds every
     * pair it takes part in, before any pair is started. Unbounded, two submissions of differently
     * repeated statements measured 19 s for the one pair at 16 KB each and 4-5 minutes at 32 KB, all
     * of it holding the permit, while each passed its tests. The worst such pair the default limit
     * admits compares in under a second.
     */
    @param:Value("\${easy.core.similarity.max-repetition}") private val maxRepetition: Long,
    /**
     * Wall-clock budget for one comparison, in seconds.
     *
     * The backstop for what the per-solution bounds do not add up: many submissions just under
     * them, or a future JPlag whose cost model differs. Read before each submission is tokenised and
     * before each pair is compared, so once the budget is spent the remaining work costs nothing: a full
     * run is refused rather than finishing and then refusing, and an optimised one on a provider that
     * bounds its pairs answers with the pairs it compared, flagged incomplete (see [PairRanking.rankBounded]).
     * Either is refused if the budget runs out before the cohort is prepared. Overshoot is bounded by what is
     * already in flight - one submission or one pair per pool thread - and each of those is bounded
     * in turn: a submission by [maxSolutionTokens] and its frontend's time limit, a pair by
     * [maxRepetition]. None can be cut short from outside once started, since no parser and not the
     * tiling polls for interruption.
     */
    @param:Value("\${easy.core.similarity.timeout-sec}") private val timeoutSec: Long,
    /** The clock budgets, paces and timings are read on: the system's, but in a test that moves its own. */
    private val ticker: Ticker = Ticker.SYSTEM,
) {
    private val log = KotlinLogging.logger {}

    private val jplag = JPlagProvider(maxSolutionTokens, maxRepetition)

    /** Every provider installed, and the choice between them; see [bind]. */
    internal var registry = SimilarityRegistry(listOf(jplag, EditDistanceProvider()))

    /** How long one check may run, in seconds: what the page says before Find. */
    val budgetSec: Long get() = timeoutSec

    /** Whether preparing, and a full run's pairs, are refused once their pace says they cannot finish; see [RunPace]. Off only in a test of the deadline. */
    internal var projectRuns = true

    /**
     * Providers whose bounds a run has caught below a score (see [PairRanking.rankBounded]): not trusted
     * again until the server restarts, so their checks compare every pair. Kept here, not stored: a wrong
     * bound is a bug in this version, which the next deploy fixes or does not.
     */
    private val untrustedBounds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The check running now, when its caller asked to watch it: see [progress]. One at a time, as the permit allows. */
    private val running = AtomicReference<RunProgress?>()

    private class RunProgress(val owner: String, val started: Long) {
        @Volatile
        var ranker: PairRanking? = null
    }

    /**
     * What [owner]'s running check has done, or null when none of theirs is running: only their own, so a
     * teacher waiting learns nothing of another's. A check is one request that answers only at the end, and
     * up to its whole budget the page had nothing to show but a greyed button.
     */
    fun progress(owner: String): SimilarityProgress? {
        val run = running.get()?.takeIf { it.owner == owner } ?: return null
        val ranker = run.ranker
        val elapsedSec = (ticker.nanos() - run.started) / 1_000_000_000
        if (ranker == null) return SimilarityProgress(false, 0, 0, elapsedSec, timeoutSec)
        val total = ranker.pairsToSettle
        return SimilarityProgress(true, minOf(ranker.settled.get(), total), total, elapsedSec, timeoutSec)
    }

    /** Whether [provider]'s bounds may rule pairs out: it has them, and no run has caught one wrong. */
    private fun bounds(provider: SimilarityProvider) = provider.bounds && provider.id !in untrustedBounds

    /** [JPlagProvider.tokenise], replaceable only by a test. */
    internal var tokenise by jplag::tokenise

    /** [JPlagProvider.comparePair], replaceable only by a test. */
    internal var comparePair by jplag::comparePair

    /** [JPlagProvider.available], replaceable only by a test. */
    internal var available by jplag::available

    /**
     * How many threads a cohort of this size runs on, replaceable only by a test - which is how the
     * answer is held to being the same on any number of them.
     *
     * Half the cores, not all of them: this runs inside the web process that also serves every
     * student and teacher request, and a pool sized to the machine measured 746% CPU on 8 cores for
     * over a minute. The single permit bounds how many comparisons run, not how much of the box one
     * of them takes. `availableProcessors` is what a container limit reports.
     */
    internal var threadsFor: (Int) -> Int =
        { cohort -> minOf((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1), cohort).coerceAtLeast(1) }

    /**
     * One comparison at a time.
     *
     * Everything — every solution and every token stream — is on core's heap for the duration, and
     * the comparison is not interruptible. Two teachers running large cohorts at once is the
     * realistic way to exhaust that heap. Bounded rather than unbounded: a caller that cannot get
     * the permit within [ACQUIRE_TIMEOUT_SEC] is refused, because parking a Tomcat request thread
     * indefinitely behind somebody else's comparison is a client timeout with nothing to act on.
     */
    private val oneAtATime = Semaphore(1, true)

    /** How many callers are waiting for [oneAtATime]; see [acquirePermit]. */
    private val waiting = AtomicInteger()

    /**
     * Refuses a cohort above the cap.
     *
     * Exposed separately so the caller can check how many submissions it is about to load *before*
     * selecting any solution's text — the controller resolves its cohort to ids first and checks
     * their count. [compare] does not check again: by then the whole corpus is already on the heap,
     * which is the allocation the bound exists to prevent, so a re-check there could only ever
     * refuse a cost already paid.
     */
    fun assertWithinLimit(count: Int) {
        if (count > maxSubmissions) throw TooManySubmissions(count, maxSubmissions)
    }

    /**
     * What a check of [sources] under [binding] would compare (EZ-1667): the submissions, the pairs among
     * them, and those in no pair at all - counted from sizes and fingerprints, never the text, so the page
     * can say it before anybody clicks. Not how long: that depends on the server and the code, and the
     * model that once said it was out by four to a hundred times; the run's own pace says it instead.
     *
     * The same pairs the engine would compare, as far as sizes can tell: identical texts once where
     * [comparands] would make them a group - by [GROUP_AUTHORS] or more authors - and each on its own
     * where it would not; none between a student's own submissions; none with a solution the provider
     * would skip, blank or for its size.
     */
    fun count(binding: SimilarityBinding, sources: List<SizedSource>): SimilarityCohort {
        val provider = checkNotNull(registry.provider(binding.provider)) { "No similarity provider ${binding.provider}" }
        return SimilarityCohort(sources.size, pairsAmong(provider, sources), sources.count { !compares(provider, it) })
    }

    /**
     * The pairs among [sources], with comparands as [comparands] makes them, with the authors of each.
     * Collapsing every identical text instead counted fifty pairs of identical solutions as fifty
     * comparands where the run compares a hundred.
     */
    private fun pairsAmong(provider: SimilarityProvider, sources: List<SizedSource>): Long {
        val comparands = sources.filter { compares(provider, it) }
            .groupBy { it.digest ?: "#${it.id}" }.values
            .flatMap { same ->
                val authors = same.mapNotNull { it.owner }.distinct().size + same.count { it.owner == null }
                if (authors >= GROUP_AUTHORS) listOf(same.mapTo(mutableSetOf()) { it.owner })
                else same.map { setOf(it.owner) }
            }
        var pairs = 0L
        for (i in comparands.indices) for (j in i + 1 until comparands.size) {
            val ownersA = comparands[i]
            val ownersB = comparands[j]
            // One author on both sides is a student against their own attempt, which is never compared.
            if (ownersA.size == 1 && ownersA == ownersB && ownersA.single() != null) continue
            pairs++
        }
        return pairs
    }

    /** Whether [source] is in any pair: not blank, which every provider skips, and of a size [provider] compares. */
    private fun compares(provider: SimilarityProvider, source: SizedSource) = !source.blank && provider.compares(source.bytes)

    /**
     * Takes a *loader* rather than the loaded solutions, so the permit covers the memory it claims to.
     *
     * The caller's solutions are the bulk of what a comparison holds, and loading them before asking
     * for the permit put every waiting caller's full cohort on the heap at once — the opposite of
     * what one permit is for. Inverting it means the rows are fetched only once this caller is the
     * one running, so at most one cohort exists at a time however many teachers click. And nothing of
     * a comparison is still running once this returns or throws, on any path, so the permit's release
     * is the end of the work and not only of the wait for it (see SimilarityWorkers).
     *
     * The loader is trusted to return no more rows than the caller passed to [assertWithinLimit].
     */
    fun <T> compare(
        loadRows: () -> List<T>,
        asSource: (T) -> SimilaritySource,
        binding: SimilarityBinding,
        baseCode: String?,
        topN: Int,
        /** Who may watch the run's [progress], if anybody. */
        owner: String? = null,
    ): SimilarityRun<T> {
        val provider = checkNotNull(registry.provider(binding.provider)) { "No similarity provider ${binding.provider}" }
        val compared = provider.comparesAs(binding.language)
        acquirePermit()
        val progress = owner?.let { RunProgress(it, ticker.nanos()) }
        running.set(progress)
        try {
            val rows = loadRows()
            if (rows.size < 2) {
                return SimilarityRun(rows, SimilarityOutcome(emptyList(), emptyList(), compared, false, provider = provider.id))
            }
            // Optimised - [SimilarityBinding.optimised] - rules out the pairs that cannot be among the top N,
            // and stops at the deadline with the likeliest found rather than refuse, where the provider can
            // bound its pairs ([SimilarityProvider.bounds]); one that cannot compares every pair either way.
            return SimilarityRun(rows, run(provider, rows.map(asSource), compared, baseCode, topN, binding.optimised && bounds(provider), binding.textAllowed, progress))
        } finally {
            running.compareAndSet(progress, null)
            oneAtATime.release()
        }
    }

    /** [compare] on JPlag, whatever the registry says: the engine's own tests, which are about JPlag. */
    internal fun <T> compare(
        loadRows: () -> List<T>,
        asSource: (T) -> SimilaritySource,
        language: SimilarityLanguage,
        baseCode: String?,
        topN: Int,
    ): SimilarityRun<T> = compare(loadRows, asSource, SimilarityBinding(jplag.id, language), baseCode, topN)

    /**
     * Which provider compares [language], as the admin has left the providers: see [SimilarityRegistry.bind].
     * Asked before the permit, so a comparison nobody may run is refused before anybody waits for it.
     */
    fun bind(language: SimilarityLanguage, pinned: String? = null): SimilarityBinding = registry.bind(language, pinned)

    /**
     * Takes the permit, waiting for it only if nobody else already is.
     *
     * A waiting caller parks a Tomcat request thread for up to [ACQUIRE_TIMEOUT_SEC]. With no bound on
     * how many could wait, that was the cheapest way to take the whole API down: some 200 parallel
     * requests on one teacher's token park every thread in Tomcat's default pool, for thirty seconds
     * at a time, at no cost to the sender. So one caller may wait behind the running comparison, and
     * any further caller is refused as busy at once - which is what the page tells a teacher anyway.
     */
    private fun acquirePermit() {
        try {
            // tryAcquire(0, ...) rather than tryAcquire(): the no-argument form takes a free permit
            // ahead of a caller already waiting for it, even on a fair semaphore.
            if (oneAtATime.tryAcquire(0, TimeUnit.SECONDS)) return
            if (waiting.incrementAndGet() > MAX_WAITING) {
                waiting.decrementAndGet()
                throw SimilarityBusy()
            }
            try {
                if (!oneAtATime.tryAcquire(ACQUIRE_TIMEOUT_SEC, TimeUnit.SECONDS)) throw SimilarityBusy()
            } finally {
                waiting.decrementAndGet()
            }
        } catch (_: InterruptedException) {
            // A server shutting down interrupts its request threads. No permit was taken, so this is
            // refused like any caller that did not get one - rather than reaching the generic handler
            // as a 500 with an ops mail - with the interrupt kept for whatever the thread does next.
            Thread.currentThread().interrupt()
            throw SimilarityBusy()
        }
    }

    private fun run(
        provider: SimilarityProvider,
        sources: List<SimilaritySource>,
        language: SimilarityLanguage,
        baseCode: String?,
        topN: Int,
        bounded: Boolean,
        textAllowed: Boolean,
        progress: RunProgress?,
    ): SimilarityOutcome {
        val deadline = Deadline.after(TimeUnit.SECONDS.toNanos(timeoutSec), ticker)
        val ownerById = sources.mapNotNull { source -> source.owner?.let { source.id to it } }.toMap()
        val threads = threadsFor(sources.size)

        SimilarityWorkers(threads, WORKER_STACK_BYTES).use { workers ->
            val context = RunContext(workers, deadline, timeoutSec, projectRuns)
            provider.open(ProviderRequest(language, sources, baseCode, textAllowed), context).use { session ->
                val prepareStart = ticker.nanos()
                val cohort = session.prepare()
                val prepareMs = (ticker.nanos() - prepareStart) / 1_000_000

                if (cohort.usable.size < 2) {
                    log.info { "Nothing to compare: ${cohort.usable.size} usable of ${sources.size}, ${cohort.skipped.size} skipped" }
                    return SimilarityOutcome(emptyList(), cohort.skipped, cohort.language, false, provider = provider.id)
                }

                val compareStart = ticker.nanos()
                val comparands = comparands(cohort, ownerById)
                val groups = comparands.filter { it.members.size > 1 }.map { it.members }
                session.ready(comparands.map { it.representative })
                val ranker = PairRanking(workers, deadline, timeoutSec, projectRuns)
                progress?.ranker = ranker
                val ranking = if (bounded) ranker.rankBounded(session, comparands, topN, partial = true) else ranker.rank(session, comparands, topN)
                ranking.boundsViolation?.let { violation ->
                    untrustedBounds += provider.id
                    log.error {
                        "Similarity provider ${provider.id} bounded a pair below its score ($violation); the pairs it ruled " +
                                "out were compared after all, and its bounds are not used again until restart"
                    }
                }
                val compareMs = (ticker.nanos() - compareStart) / 1_000_000

                val comparandById = comparands.associateBy { it.representative }
                val pairs = ranking.top.map { pairOf(it.pair, comparandById, ownerById, session) }

                log.info {
                    "Compared ${cohort.usable.size} submissions (${cohort.language.wireName}, ${provider.id}) " +
                            "in ${prepareMs}ms preparing + ${compareMs}ms comparing on $threads thread(s), " +
                            "${cohort.skipped.size} skipped, ${groups.size} identical group(s), ${ranking.scoring} scoring " +
                            "pairs of ${ranking.compared} compared of ${ranking.total}" +
                            "${if (bounded) " (optimised)" else ""}${if (ranking.complete) "" else ", stopped at the deadline"}, " +
                            "returning ${pairs.size}"
                }
                return SimilarityOutcome(
                    pairs, cohort.skipped, cohort.language, ranking.scoring > pairs.size || ranking.mayHaveMore, groups, provider.id,
                    pairsTotal = ranking.total, pairsCompared = ranking.compared, complete = ranking.complete,
                )
            }
        }
    }

    /**
     * The cohort as it is compared: each submission on its own, except that identical ones - as the
     * provider defines identical (see [PreparedCohort]) - handed in by at least [GROUP_AUTHORS] different
     * authors are one [Comparand], compared once and reported as a group.
     *
     * Twelve students handing in the starter code unchanged made 66 pairs at 100% among themselves and
     * twelve more against every other student: the page's top N filled before a real finding was reached.
     * A copying ring is the same shape, and reads better as one line too - fifteen identical copies were
     * 105 rows. Two identical submissions stay a pair, which the page shows side by side; and one student's
     * identical attempts are not a group, since they are never reported against each other anyway.
     *
     * In the cohort's order, so pairs keep the orientation they always had; a group where its earliest
     * member was. A group's members are in id order, the lowest first and compared for them all, so that
     * which member speaks for a group does not depend on the order rows came out of the database in.
     */
    private fun comparands(cohort: PreparedCohort, ownerById: Map<Long, String>): List<Comparand> {
        val ids = cohort.usable
        val byKey = LinkedHashMap<Long, MutableList<MutableList<Long>>>()
        for (id in ids) {
            val alike = byKey.getOrPut(cohort.groupKey(id)) { mutableListOf() }
            val same = alike.firstOrNull { cohort.identical(it.first(), id) }
            if (same != null) same += id else alike += mutableListOf(id)
        }
        val position = ids.withIndex().associate { (index, id) -> id to index }
        return byKey.values.flatten().flatMap { same ->
            // An unknown author differs from everyone, so each one counts.
            val authors = same.mapNotNull { ownerById[it] }.distinct().size + same.count { ownerById[it] == null }
            if (authors >= GROUP_AUTHORS) {
                val members = same.sorted()
                listOf(Comparand(members.first(), members, members.map { ownerById[it] }))
            } else {
                same.map { Comparand(it, listOf(it), listOf(ownerById[it])) }
            }
        }.sortedBy { comparand -> comparand.members.minOf { position.getValue(it) } }
    }

    /**
     * A ranked pair of comparands as the two submissions to show: their first members, or - where those
     * have one author, a student's own attempt inside a group against another - the first two whose
     * authors differ, which [Comparand.apartFrom] is what let the pair be compared at all. The sides are
     * as the provider oriented the pair, and the lines are read from the members shown.
     */
    private fun pairOf(
        pair: PairScore,
        comparandById: Map<Long, Comparand>,
        ownerById: Map<Long, String>,
        session: ProviderSession,
    ): SimilarityPair {
        val first = comparandById.getValue(pair.first)
        val second = comparandById.getValue(pair.second)
        val (id1, id2) = first.members.asSequence()
            .flatMap { a -> second.members.asSequence().map { b -> a to b } }
            .first { (a, b) -> ownerById[a] == null || ownerById[b] == null || ownerById[a] != ownerById[b] }
        return SimilarityPair(id1, id2, pair.similarity, pair.score, session.details(pair, id1, id2), session.metrics(pair))
    }

    companion object {
        /** Long enough to ride out a normal comparison, short enough not to look like a hang. */
        private const val ACQUIRE_TIMEOUT_SEC = 30L

        /** Callers that may wait for the permit at once; see [acquirePermit]. */
        private const val MAX_WAITING = 1

        /** How many different authors of one identical submission make them a group; see [comparands]. */
        private const val GROUP_AUTHORS = 3

        /**
         * The pool's thread stack: 64 MB rather than the JVM's default of 1 MB.
         *
         * Every frontend with a parser is recursive descent, walked recursively, and a default stack is
         * too shallow for code that runs. CPython 3.14 compiles `not` repeated 5,967 times or `lambda:`
         * 2,983 times, the most it accepts, and both overflowed 1 MB; javac on 1 MB overflowed on 5,000
         * nested parentheses or casts, and on a 5,000-term sum. An overflow is a CANNOT_PARSE skip, so
         * one such line appended to a copy took it out of the comparison while it still passed its
         * tests. On 64 MB every construct measured parses in a second or two up to the default
         * max-solution-tokens.
         *
         * A deeper stack has one cost besides address space: a nesting that used to overflow quickly now
         * runs to the end. For Python's brackets that end took minutes, which is why they are capped at
         * CPython's own 200 before parsing. Address space is all the rest costs - pages are committed only
         * as the stack reaches them, and go back with the thread at the end of the run.
         */
        private const val WORKER_STACK_BYTES = 64L shl 20
    }
}
