package core.ems.service.code.similarity

import de.jplag.JPlagComparison
import de.jplag.Language
import de.jplag.ParsingException
import de.jplag.Submission
import de.jplag.SubmissionSet
import de.jplag.SubmissionState
import de.jplag.Token
import de.jplag.TokenType
import de.jplag.comparison.GreedyStringTiling
import de.jplag.logging.ProgressBar
import de.jplag.logging.ProgressBarLogger
import de.jplag.options.JPlagOptions
import de.jplag.options.LanguageOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Token-based similarity, entirely in memory: which submissions of a cohort are most alike.
 *
 * The pieces, each in a file of its own:
 *
 *  - a [SimilarityFrontend] per [SimilarityLanguage] turns one solution into tokens. That is the only
 *    language-specific code there is, and nothing here depends on which language it is;
 *  - [SimilarityWorkers] runs a comparison's work in parallel, and holds every rule about doing so;
 *  - [PairRanking] compares the pairs and keeps the best;
 *  - this class admits one comparison at a time, bounds what each can cost, and hands JPlag the tokens.
 *
 * JPlag's documented entry point cannot be used: `JPlagOptions.submissionDirectories` is `Set<File>`,
 * and every read funnels through `de.jplag.util.FileUtils`, which uses `new FileInputStream(file)`
 * and `new FileReader(file, charset)` — both native, both taking only the path string, so no `File`
 * subclass or custom `FileSystemProvider` can intercept them. Handing JPlag a submission the normal
 * way would mean writing student code to disk.
 *
 * The way through is to leave JPlag's pipeline intact and replace only the *source of tokens*.
 * [PretokenisedLanguage] is a `de.jplag.Language` whose `parse()` returns tokens the frontends already
 * produced in memory. `Submission.parse()` then runs and succeeds normally, which matters more than it
 * sounds:
 *
 *  - JPlag sets the submission state itself, so `VALID` / `CANNOT_PARSE` / `TOO_SMALL` keep their
 *    real meanings and `SubmissionSet.getInvalidSubmissions()` is the honest list of what was dropped.
 *  - Base code goes through `parseBaseCodeSubmission` like any other submission instead of throwing
 *    `BasecodeException`.
 *  - Nothing opens a file. The [File] objects below are identifiers: they are the keys
 *    [PretokenisedLanguage] looks tokens up by, they need not exist, and nothing reads them.
 *
 * An earlier version built the submissions and forced `getState()` to `VALID`. That was wrong:
 * `SubmissionSet`'s constructor parses every submission regardless of who built it, so the parse was
 * still happening, still hitting the filesystem, and still failing — the override merely hid it, and
 * hiding it turned the base-code path into an HTTP 500.
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
     * consecutive token types (see [repetition]). A solution that repeats no run scores about its own
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
     * before each pair is compared, so once the budget is spent the remaining work costs nothing and
     * the run is refused instead of finishing and then refusing. Overshoot is bounded by what is
     * already in flight - one submission or one pair per pool thread - and each of those is bounded
     * in turn: a submission by [maxSolutionTokens] and its frontend's time limit, a pair by
     * [maxRepetition]. None can be cut short from outside once started, since no parser and not the
     * tiling polls for interruption.
     */
    @param:Value("\${easy.core.similarity.timeout-sec}") private val timeoutSec: Long,
) {
    private val log = KotlinLogging.logger {}

    /**
     * The tokeniser, replaceable only by a test. The catches that turn a tokeniser that *throws* into
     * a CANNOT_PARSE skip need an input that makes a frontend throw, and none does reliably: the case
     * JPlag's own Python code names, an empty TrailerContext, needs error recovery to enter a rule it
     * cannot start. Without a way in, those catches could be deleted with every test green.
     */
    internal var tokenise: (SimilarityLanguage, File, String, Int) -> Tokenised =
        { language, id, solution, maxTokens -> language.read(id, solution, maxTokens) }

    /**
     * The comparison of one pair, replaceable only by a test. The per-pair deadline check matters
     * exactly when pairs are slow, and a real slow pair is what [maxRepetition] now refuses to run -
     * so a test needs another way to make the comparison outlast the budget.
     */
    internal var comparePair: (GreedyStringTiling, Submission, Submission) -> JPlagComparison =
        { gst, first, second -> gst.compare(first, second) }

    /** Whether a language's frontend can run in this JVM, replaceable only by a test: see [comparable]. */
    internal var available: (SimilarityLanguage) -> Boolean = { it.frontend.available }

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

    init {
        // JPlag's default progress-bar provider logs one INFO line per step, and its comparison
        // driver steps once per pair. At max-submissions: 500 that is 500*499/2 = 124,750 INFO lines
        // from many virtual threads, to both the console and the rolling file appender - roughly 12 MB
        // per click, and enough appender contention to outweigh the comparison itself. There is no
        // logger-level fix, because the lines are emitted under JPlag's own category at INFO and so
        // is everything else it says. Replacing the provider is the supported way to turn it off.
        // (The pairs are no longer compared by JPlag's driver, but TokenSequenceMapper and
        // SubmissionSet still step one.)
        ProgressBarLogger.setProgressBarProvider { _, _ ->
            object : ProgressBar {
                override fun step(number: Int) = Unit
                override fun dispose() = Unit
            }
        }
        // Said at startup rather than on the first Java cohort, which may be months later.
        SimilarityLanguage.entries.filterNot { it.frontend.available }.forEach {
            log.warn { "Similarity cannot tokenise ${it.wireName} in this JVM and will compare it as text" }
        }
    }

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
     * Feeds JPlag tokens this service already has, instead of letting it read files.
     *
     * `parse()` is the only abstract method that matters here; the rest of [Language] is metadata.
     * A file with no entry is a submission whose tokenisation threw, and the [ParsingException] is
     * exactly what JPlag's own language modules raise — it marks that one submission `CANNOT_PARSE`
     * and carries on with the rest.
     */
    private class PretokenisedLanguage(
        private val tokensByFile: Map<File, List<Token>>,
        private val minimumTokenMatch: Int,
    ) : Language {
        override fun fileExtensions(): List<String> = emptyList()
        override fun getName(): String = "easy-pretokenised"
        override fun getIdentifier(): String = "easy-pretokenised"
        override fun minimumTokenMatch(): Int = minimumTokenMatch
        override fun getOptions(): LanguageOptions = LanguageOptions.EMPTY_OPTIONS

        // Sorted, not just flat-mapped: Submission.parse hands this a HashSet, so iteration order is
        // unspecified. One file per submission today, but the moment that stops being true an
        // unsorted assembly would make the same submission's token stream - and therefore its
        // similarity scores - differ between runs. JPlag's own adapter appends one FILE_END per file,
        // which is why getSimilarityDivisor subtracts files.size rather than 1, so concatenating
        // several files' token lists is the right shape; only the order needed pinning.
        override fun parse(files: Set<File>, normalize: Boolean): List<Token> =
            files.sortedBy { it.path }
                .flatMap { tokensByFile[it] ?: throw ParsingException(it, "not tokenised in memory") }
    }

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
        language: SimilarityLanguage,
        baseCode: String?,
        topN: Int,
    ): SimilarityRun<T> {
        val compared = comparable(language)
        acquirePermit()
        try {
            val rows = loadRows()
            if (rows.size < 2) {
                return SimilarityRun(rows, SimilarityOutcome(emptyList(), emptyList(), compared, false))
            }
            return SimilarityRun(rows, run(rows.map(asSource), compared, baseCode, topN))
        } finally {
            oneAtATime.release()
        }
    }

    /**
     * [language], or text if its frontend cannot run in this JVM - Java's needs javac, which a JRE may
     * leave out. A word-level comparison is a weaker answer, and the response names it as text so the
     * page says so, but it is an answer; refusing would leave the teacher with none.
     */
    private fun comparable(language: SimilarityLanguage): SimilarityLanguage {
        if (available(language)) return language
        log.warn { "Similarity cannot tokenise ${language.wireName} in this JVM; comparing as text" }
        return SimilarityLanguage.TEXT
    }

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
        sources: List<SimilaritySource>,
        requested: SimilarityLanguage,
        baseCode: String?,
        topN: Int,
    ): SimilarityOutcome {
        val deadline = Deadline.after(TimeUnit.SECONDS.toNanos(timeoutSec))
        val files = sources.map { File("submission-${it.id}") }
        val idByName = sources.associate { it.id.toString() to it.id }
        val ownerById = sources.mapNotNull { source -> source.owner?.let { source.id to it } }.toMap()
        val template = baseCode?.takeIf { it.isNotBlank() }
        val threads = threadsFor(sources.size)

        SimilarityWorkers(threads, WORKER_STACK_BYTES).use { workers ->
            val tokeniseStart = System.nanoTime()
            // The template too, when there is one, on the pool like everything else: it is parsed inside
            // the permit, cannot be interrupted, and needs the pool's stack as much as a submission does.
            fun tokenise(language: SimilarityLanguage, indices: IntRange, withTemplate: Boolean) = workers.all(
                indices.map { i ->
                    { tokeniseOne("Submission ${sources[i].id}", files[i], sources[i].solution, language, deadline) }
                } + listOfNotNull(template?.takeIf { withTemplate }?.let { { tokeniseOne("The exercise template", BASE_CODE, it, language, deadline) } }),
            )

            // A sample first, so a cohort that is not in its language at all - an essay exercise that kept
            // `lahendus.py` - is not parsed in full only to be thrown away: that pass cost twice what the
            // text comparison did, inside the same budget, and could time out a run text alone finishes.
            // The whole cohort still decides when the sample does not.
            var language = requested
            val sample = minOf(sources.size, LANGUAGE_SAMPLE)
            val sampled = tokenise(language, 0 until sample, withTemplate = false)
            var results = if (language != SimilarityLanguage.TEXT && mostlyNotIn(sampled)) sampled
            else sampled + tokenise(language, sample until sources.size, withTemplate = true)
            if (language != SimilarityLanguage.TEXT && mostlyNotIn(results.take(sources.size))) {
                log.info { "Most of this cohort did not parse as ${language.wireName}; comparing it as text instead" }
                language = SimilarityLanguage.TEXT
                results = tokenise(language, sources.indices, withTemplate = true)
            }
            val minTokenMatch = language.frontend.minimumTokenMatch

            // Assembled in source order, not completion order, so `skipped` and every downstream list
            // is identical run to run.
            val tokensByFile = HashMap<File, List<Token>>()
            val refused = mutableListOf<SkippedSubmission>()
            sources.forEachIndexed { i, source ->
                when (val result = results[i]) {
                    is TokeniseResult.Ok -> tokensByFile[files[i]] = result.tokens
                    TokeniseResult.TooLarge -> refused += SkippedSubmission(source.id, "TOO_LARGE")
                    TokeniseResult.TooComplex -> refused += SkippedSubmission(source.id, "TOO_COMPLEX")
                    TokeniseResult.TooRepetitive -> refused += SkippedSubmission(source.id, "TOO_REPETITIVE")
                    is TokeniseResult.TooSmall -> refused += SkippedSubmission(source.id, "TOO_SMALL")
                    // Left out of tokensByFile entirely; JPlag then marks it CANNOT_PARSE itself.
                    TokeniseResult.Failed -> Unit
                }
            }

            // Also checked here, for the case where every task started inside the budget and the last
            // of them ran past it.
            if (deadline.passed()) throw SimilarityTimedOut(timeoutSec)

            // Starter code every submission was built on, when the caller has some. Subtracted as base
            // code, it is not reported as similarity, so a fill-in-the-blank exercise does not flag its
            // whole cohort. The endpoint passes none today; see where TeacherCheckSimilarity calls this.
            val baseCodeFile = template?.let { usableTemplate(results.last(), minTokenMatch) }?.let { tokens ->
                tokensByFile[BASE_CODE] = tokens
                BASE_CODE
            }

            val tokeniseMs = (System.nanoTime() - tokeniseStart) / 1_000_000
            // Snapshotted, not handed over live: SubmissionSet parses submissions in parallel, one virtual
            // thread each, so PretokenisedLanguage.parse reads this map concurrently. The copy makes the
            // read-only intent true rather than merely declared by the parameter type - tokenising lazily
            // inside parse() is the obvious next optimisation, and it would turn those reads into
            // concurrent writes on a HashMap, which corrupts under load and never in a test.
            val jplagLanguage = PretokenisedLanguage(tokensByFile.toMap(), minTokenMatch)
            // Refused submissions are left out of the set entirely rather than handed over untokenised.
            // PretokenisedLanguage answers a missing file with a ParsingException, so passing them would
            // get them a second, wrong verdict: reported once here as TOO_LARGE and again by JPlag as
            // CANNOT_PARSE, which tells the teacher the code was broken when it was merely too long.
            val refusedIds = refused.mapTo(mutableSetOf()) { it.id }
            val submissions = sources.indices.filterNot { sources[it].id in refusedIds }.map { i ->
                Submission(sources[i].id.toString(), files[i], true, listOf(files[i]), jplagLanguage)
            }
            val base = baseCodeFile?.let { Submission("basecode", it, true, listOf(it), jplagLanguage) }
            val options = JPlagOptions(jplagLanguage, emptySet(), emptySet()).withMinimumTokenMatch(minTokenMatch)

            // Parses every submission through PretokenisedLanguage and assigns each a real state.
            val set = SubmissionSet(submissions, base, options)

            // JPlag's own verdict on what could not be used, rather than a hand-rolled re-implementation
            // of rules it already applies. Base code is not in this list; it is handled separately above.
            // The *reason* is translated rather than passed through — see [skipReason].
            val skipped = refused + set.invalidSubmissions.mapNotNull { invalid ->
                idByName[invalid.name]?.let { SkippedSubmission(it, skipReason(invalid.state)) }
            }

            if (set.numberOfSubmissions() < 2) {
                log.info { "Nothing to compare: ${set.numberOfSubmissions()} usable of ${sources.size}, ${skipped.size} skipped" }
                return SimilarityOutcome(emptyList(), skipped, language, false)
            }

            val compareStart = System.nanoTime()
            val submissionById = set.submissions.associateBy { idByName.getValue(it.name) }
            val solutionById = sources.associate { it.id to it.solution }
            val comparands = comparands(set.submissions.map { idByName.getValue(it.name) }, submissionById, ownerById, solutionById)
            val groups = comparands.filter { it.members.size > 1 }.map { it.members }
            val ranking = PairRanking(workers, deadline, timeoutSec, comparePair).rank(set, options, comparands, topN)
            val compareMs = (System.nanoTime() - compareStart) / 1_000_000

            val comparandByName = comparands.associateBy { it.submission.name }
            val pairs = ranking.top.map { pairOf(it, comparandByName, submissionById, ownerById) }

            log.info {
                "Compared ${set.numberOfSubmissions()} submissions (${language.wireName}) " +
                        "in ${tokeniseMs}ms tokenising + ${compareMs}ms comparing on $threads thread(s), " +
                        "${skipped.size} skipped, ${groups.size} identical group(s), ${ranking.scoring} scoring " +
                        "pairs of ${ranking.compared} compared, returning ${pairs.size}"
            }
            return SimilarityOutcome(pairs, skipped, language, ranking.scoring > pairs.size, groups)
        }
    }

    /**
     * The cohort as it is compared: each submission on its own, except that identical ones - the same
     * text once layout is set aside, and so the same tokens - handed in by at least [GROUP_AUTHORS]
     * different authors are one [Comparand], compared once and reported as a group.
     *
     * The text, not only the tokens: Python's and Java's token types leave out operators, literals and
     * names, so a max, a min and a sum over a list were one "identical code" group, shown as the first
     * member's code alone, with nothing to say the others differed.
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
    private fun comparands(
        ids: List<Long>,
        submissionById: Map<Long, Submission>,
        ownerById: Map<Long, String>,
        solutionById: Map<Long, String>,
    ): List<Comparand> {
        // By a fingerprint of the token types, and by the types themselves only within one: keyed on the
        // types, grouping held a second copy of every token list in the cohort while it ran.
        val byFingerprint = LinkedHashMap<Long, MutableList<MutableList<Long>>>()
        for (id in ids) {
            val tokens = submissionById.getValue(id).tokenList
            val alike = byFingerprint.getOrPut(fingerprint(tokens)) { mutableListOf() }
            val same = alike.firstOrNull {
                sameTypes(submissionById.getValue(it.first()).tokenList, tokens) &&
                        sameText(solutionById.getValue(it.first()), solutionById.getValue(id))
            }
            if (same != null) same += id else alike += mutableListOf(id)
        }
        val position = ids.withIndex().associate { (index, id) -> id to index }
        return byFingerprint.values.flatten().flatMap { same ->
            // An unknown author differs from everyone, so each one counts.
            val authors = same.mapNotNull { ownerById[it] }.distinct().size + same.count { ownerById[it] == null }
            if (authors >= GROUP_AUTHORS) {
                val members = same.sorted()
                listOf(Comparand(submissionById.getValue(members.first()), members, members.map { ownerById[it] }))
            } else {
                same.map { Comparand(submissionById.getValue(it), listOf(it), listOf(ownerById[it])) }
            }
        }.sortedBy { comparand -> comparand.members.minOf { position.getValue(it) } }
    }

    /** The types [tokens] would be matched on, hashed: equal for identical submissions, rarely otherwise. */
    private fun fingerprint(tokens: List<Token>): Long {
        var hash = 1L
        for (token in tokens) if (!token.type.isExcludedFromMatching()) hash = hash * 1_000_003L + token.type.hashCode()
        return hash
    }

    /** Whether two solutions are the same text but for layout: runs of whitespace, and where they start and end. */
    private fun sameText(first: String, second: String): Boolean =
        first.trim().replace(LAYOUT, " ") == second.trim().replace(LAYOUT, " ")

    /** Whether two token lists match on the same types in the same order, which a group's members must too. */
    private fun sameTypes(first: List<Token>, second: List<Token>): Boolean {
        val a = first.asSequence().map { it.type }.filterNot { it.isExcludedFromMatching() }.iterator()
        val b = second.asSequence().map { it.type }.filterNot { it.isExcludedFromMatching() }.iterator()
        while (a.hasNext() && b.hasNext()) if (a.next() != b.next()) return false
        return !a.hasNext() && !b.hasNext()
    }

    /**
     * A ranked pair of comparands as the two submissions to show: their first members, or - where those
     * have one author, a student's own attempt inside a group against another - the first two whose
     * authors differ, which [Comparand.apartFrom] is what let the pair be compared at all. A group's
     * members are token for token the same, so the tiling's matches index any member's tokens alike, and
     * the lines are read from the members shown.
     */
    private fun pairOf(
        ranked: Ranked,
        comparandByName: Map<String, Comparand>,
        submissionById: Map<Long, Submission>,
        ownerById: Map<Long, String>,
    ): SimilarityPair {
        val comparison = ranked.comparison
        val first = comparandByName.getValue(comparison.firstSubmission().name)
        val second = comparandByName.getValue(comparison.secondSubmission().name)
        val (id1, id2) = first.members.asSequence()
            .flatMap { a -> second.members.asSequence().map { b -> a to b } }
            .first { (a, b) -> ownerById[a] == null || ownerById[b] == null || ownerById[a] != ownerById[b] }
        val tokens1 = submissionById.getValue(id1).tokenList
        val tokens2 = submissionById.getValue(id2).tokenList
        return SimilarityPair(
            id1, id2, ranked.average, ranked.maximum,
            comparison.matches().map { match ->
                SimilarityMatch(
                    lineAt(tokens1, match.startOfFirst()),
                    endLineAt(tokens1, match.startOfFirst() + match.lengthOfFirst() - 1),
                    lineAt(tokens2, match.startOfSecond()),
                    endLineAt(tokens2, match.startOfSecond() + match.lengthOfSecond() - 1),
                    match.minimumLength(),
                )
            },
        )
    }

    /**
     * The template's tokens, if it can be used as base code; otherwise null, and a line saying why.
     *
     * SubmissionSet refuses a base code shorter than the shortest match it would look for, and it
     * refuses by throwing BasecodeException out of the constructor - a 500 for the whole request, not a
     * degraded one. A template like "# write your solution here" is a single token, so this is the
     * common case rather than an edge: drop the base code and compare without it. Checked here because
     * JPlag gives no way to ask it politely. A template refused on cost is dropped the same way,
     * including for repetition: it is tiled against every submission, so it would cost what such a
     * submission does.
     */
    private fun usableTemplate(result: TokeniseResult, minTokenMatch: Int): List<Token>? {
        if (result !is TokeniseResult.Ok) {
            log.info { "Exercise template not usable as base code ($result); comparing without it" }
            return null
        }
        if (result.tokens.size < minTokenMatch) {
            log.info {
                "Exercise template is ${result.tokens.size} token(s), below the minimum match of " +
                        "$minTokenMatch; comparing without base code"
            }
            return null
        }
        return result.tokens
    }

    /**
     * Whether most of a cohort is not in the language it was read as: its parser had to recover from
     * syntax errors to tokenise a solution, or could not tokenise it at all.
     *
     * One such solution is compared like any other (see [Tokenised]); most of them mean the exercise is
     * not in that language - an essay exercise that kept the `lahendus.py` every exercise starts as, whose
     * prose the Python parser recovered into one identical token per line, so that unrelated essays paired
     * at 90-100%. Compared as text instead, and the outcome says text. Solutions refused on cost say
     * nothing about their language and are not counted either way.
     */
    private fun mostlyNotIn(results: List<TokeniseResult>): Boolean {
        // A solution too small to compare still says whether it was in the language: one-sentence answers
        // read as Python are, and left out of both counts, a short-answer cohort stayed Python, every
        // answer skipped as too small.
        val read = results.count { it is TokeniseResult.Ok || it is TokeniseResult.TooSmall || it == TokeniseResult.Failed }
        val notIn = results.count {
            (it is TokeniseResult.Ok && it.recovered) || (it is TokeniseResult.TooSmall && it.recovered) || it == TokeniseResult.Failed
        }
        return notIn * 2 > read
    }

    /** What became of one solution's tokenisation, so the outcome can be assembled in order. */
    private sealed interface TokeniseResult {
        data class Ok(val tokens: List<Token>, val recovered: Boolean) : TokeniseResult {
            override fun toString() = "${tokens.size} tokens"
        }

        data object TooLarge : TokeniseResult
        data object TooComplex : TokeniseResult
        data object TooRepetitive : TokeniseResult
        /** Still says whether recovery took it over: a short answer read as Python is both. */
        data class TooSmall(val recovered: Boolean) : TokeniseResult
        data object Failed : TokeniseResult
    }

    /**
     * One solution's tokens, or why there are none. Runs on a pool thread, for a submission or for
     * the template, which [label] names in the log.
     *
     * It touches nothing shared but what is built to be shared: its arguments are its own, the
     * service's fields are read-only once constructed, and the frontends' shared state - Python's
     * lexer and parser DFAs - is locked by ANTLR internally (see PythonLexerCache).
     *
     * The deadline is read here, before each solution, so that once the budget is spent the rest cost
     * nothing: parallelising would otherwise have turned an early refusal into "tokenise the whole
     * cohort, then refuse" - the permit held for the full run, the opposite of what the budget is for.
     */
    private fun tokeniseOne(
        label: String,
        id: File,
        solution: String,
        language: SimilarityLanguage,
        deadline: Deadline,
    ): TokeniseResult {
        if (deadline.passed()) throw SimilarityTimedOut(timeoutSec)

        // Deliberately not runCatching: that catches Throwable, so an OutOfMemoryError part way
        // through a large cohort would be filed as an ordinary CANNOT_PARSE skip and the endpoint
        // would answer 200 with every remaining submission "skipped" - indistinguishable from a
        // cohort that genuinely would not parse. An OOM must reach the error handler, which it does:
        // SimilarityWorkers reports it over any other failure.
        val tokenised = try {
            tokenise(language, id, solution, maxSolutionTokens)
        } catch (_: SolutionTooLarge) {
            log.info { "$label is above the $maxSolutionTokens-token similarity limit; skipping it" }
            return TokeniseResult.TooLarge
        } catch (e: SolutionTooComplex) {
            log.info { "$label would take too long to tokenise (${e.message}); skipping it" }
            return TokeniseResult.TooComplex
        } catch (e: SimilarityParseException) {
            log.debug(e) { "$label could not be tokenised" }
            return TokeniseResult.Failed
        } catch (_: StackOverflowError) {
            // A recursive-descent parser, or a tree walked recursively, overflows on nesting deep
            // enough - though at the default limits nothing that fits under maxSolutionTokens does on
            // this pool's stack (see WORKER_STACK_BYTES). One submission's problem, not the cohort's,
            // and on its own thread, so the unwound stack is that thread's, not the request's.
            //
            // Logged without the Error itself. Its trace is 1,024 frames of the one recursion, a line
            // each, per submission - a cohort built to overflow wrote megabytes a click, and none of it
            // says more than this line does.
            log.warn { "$label overflowed the parser stack" }
            return TokeniseResult.Failed
        }

        // Fewer tokens to match on than the shortest match: nothing in it can ever match. JPlag's own
        // TOO_SMALL counts FILE_END, so one token short of the minimum was valid but unmatchable -
        // two copies of a one-query answer came back with no pair and no skip, and three as an
        // identical group "every pair of which would score 100%".
        if (tokenised.tokens.count { !it.type.isExcludedFromMatching() } < language.frontend.minimumTokenMatch) {
            return TokeniseResult.TooSmall(tokenised.recovered)
        }
        val repetition = repetition(tokenised.tokens, language.frontend.minimumTokenMatch)
        if (repetition > maxRepetition) {
            log.info { "$label repeats itself too much to compare ($repetition, limit $maxRepetition); skipping it" }
            return TokeniseResult.TooRepetitive
        }
        return TokeniseResult.Ok(tokenised.tokens, tokenised.recovered)
    }

    /**
     * Σ c² over the count c of each distinct run of [window] consecutive token types — the quantity
     * [maxRepetition] bounds, and why it bounds a pair is set out there.
     *
     * Over types because types are all the tiling compares, in runs of the minimum match length
     * because that is the window it looks runs up by, and without FILE_END, which it never matches.
     * Exact runs rather than JPlag's hash of them: its hash puts some distinct runs in one bucket, so
     * the visits it makes can run to a small multiple of this, but it cannot make them match, and a
     * pass that matches nothing is the last. Keyed on sublist views, which compare by element, so
     * nothing is copied.
     */
    private fun repetition(tokens: List<Token>, window: Int): Long {
        val types: List<TokenType> = tokens.map { it.type }.filterNot { it.isExcludedFromMatching() }
        val counts = HashMap<List<TokenType>, Int>()
        for (start in 0..types.size - window) {
            counts.merge(types.subList(start, start + window), 1, Int::plus)
        }
        return counts.values.sumOf { it.toLong() * it }
    }

    /**
     * Our own name for why a submission was dropped, rather than JPlag's enum constant.
     *
     * `invalid.state.name` used to go straight onto the wire, and the page filters on the literals
     * it produced. That made a third-party rename silently empty the breakdown line while
     * submissions went on being dropped — no test could see it, because the response *shape* is
     * unchanged and only the values move. Written without an `else` on purpose: a JPlag upgrade that
     * adds a state is then a compile error here, at the one place that has to decide what to call it.
     *
     * Only `CANNOT_PARSE` reaches this in practice, beside the `TOO_LARGE`, `TOO_COMPLEX`,
     * `TOO_REPETITIVE` and `TOO_SMALL` that [run] assigns itself - the last before JPlag would, since
     * JPlag's counts `FILE_END` (see [tokeniseOne]). An empty solution is not `NOTHING_TO_PARSE`: every
     * submission is built with exactly one file, so JPlag always has something to parse.
     */
    private fun skipReason(state: SubmissionState): String = when (state) {
        SubmissionState.CANNOT_PARSE -> "CANNOT_PARSE"
        SubmissionState.TOO_SMALL -> "TOO_SMALL"
        // Unreachable here (see above); mapped rather than elided so the `when` stays exhaustive.
        SubmissionState.NOTHING_TO_PARSE -> "EMPTY"
        // Not invalid by definition, so never in getInvalidSubmissions().
        SubmissionState.VALID -> "UNUSABLE"
        // Reachable, and not a skip. SubmissionSet parses on virtual threads and never reads their
        // futures, so a Throwable inside Submission.parse — an OutOfMemoryError copying a token list
        // is the realistic one — leaves the submission UNPARSED with nothing logged. Filing that as a
        // skip would answer 200 over a dead heap, which is exactly what tokeniseOne refuses to do.
        SubmissionState.UNPARSED -> throw IllegalStateException(
            "JPlag left a submission unparsed, so its parse task failed; refusing to report it as skipped"
        )
    }

    /**
     * A [de.jplag.Match] indexes tokens, not lines, and a reader needs lines.
     *
     * Out of range answers 0 rather than clamping onto the last token, which is always `FILE_END` —
     * and JPlag builds that one with `Token.NO_VALUE` for the line, so clamping returned **-1** and
     * handed a negative line number to the UI. 0 is the "no line" the callers already expect. An
     * out-of-range index would mean a JPlag change we have not noticed; losing one highlight is
     * still a better failure than losing the comparison.
     */
    private fun lineAt(tokens: List<Token>, index: Int): Int =
        tokens.getOrNull(index)?.startLine?.takeIf { it > 0 } ?: 0

    /**
     * The line a match's last token ends on, read as [lineAt] reads where one starts. Its start line made
     * a match that ended on a multi-line SQL string end where the string began, the rest left unshaded.
     */
    private fun endLineAt(tokens: List<Token>, index: Int): Int =
        tokens.getOrNull(index)?.let { maxOf(it.endLine, it.startLine) }?.takeIf { it > 0 } ?: 0

    companion object {
        /** Long enough to ride out a normal comparison, short enough not to look like a hang. */
        private const val ACQUIRE_TIMEOUT_SEC = 30L

        /** Callers that may wait for the permit at once; see [acquirePermit]. */
        private const val MAX_WAITING = 1

        /** How many solutions decide alone that a cohort is not in its language; see [run]. */
        private const val LANGUAGE_SAMPLE = 20

        private val LAYOUT = Regex("\\s+")

        /** How many different authors of one identical submission make them a group; see [comparands]. */
        private const val GROUP_AUTHORS = 3

        /** The template's identifier, beside the submissions'. */
        private val BASE_CODE = File("basecode")

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
