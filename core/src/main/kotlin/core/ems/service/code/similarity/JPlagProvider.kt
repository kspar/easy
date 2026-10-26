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
import de.jplag.comparison.TokenSequenceMapper
import de.jplag.logging.ProgressBar
import de.jplag.logging.ProgressBarLogger
import de.jplag.options.JPlagOptions
import de.jplag.options.LanguageOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Similarity as JPlag measures it: Greedy String Tiling over each language's tokens, so that renaming,
 * reformatting and reordering what does not matter changes nothing.
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
 *
 * The comparison of pairs is JPlag's algorithm but not its driver; [SimilarityService] and
 * [PairRanking] run the pairs, and [Session.score] is one tiling.
 *
 * [maxSolutionTokens] and [maxRepetition] are the bounds documented on [SimilarityService]'s
 * constructor, which reads them from configuration.
 */
internal class JPlagProvider(
    private val maxSolutionTokens: Int,
    private val maxRepetition: Long,
) : SimilarityProvider {
    private val log = KotlinLogging.logger {}

    override val id = ID

    /** SQL's lexer is core's own, and every review found a way past it; an admin vouches for it first. */
    override val languages = mapOf(
        SimilarityLanguage.PYTHON to LanguageSupport.STABLE,
        SimilarityLanguage.JAVA to LanguageSupport.STABLE,
        SimilarityLanguage.SQL to LanguageSupport.EXPERIMENTAL,
        SimilarityLanguage.TEXT to LanguageSupport.STABLE,
    )

    /** Below edit distance until an admin raises it: deploying this changes no course's scores by itself. */
    override val defaultPriority = 50

    /** The tiling's matched token runs, as line ranges. */
    override val matchRanges = true

    /**
     * From the token runs two submissions share, [TokenRuns]: a bound on the matched tokens, and so on every
     * number the tiling gives, cheaply enough to order every pair by it. The optimised mode then compares the
     * likeliest pairs first, rules out those that cannot reach the top, and stops at the deadline with what it
     * found - where before a JPlag run compared every pair, and one too large for the budget was refused.
     */
    override val bounds = true

    /**
     * Tokenising: about 20 ms a solution whatever its size, and 2 µs a character on top, measured on
     * Python of 2 to 20 KB on one core. Tokens are not known before it, so the cost is read from
     * characters.
     */
    override fun prepareNanos(bytes: Int): Long = 20_000_000L + 2_000L * bytes

    /**
     * One tiling: about 70 µs, a few nanoseconds a character, and a term in the product of the two sizes,
     * since greedy string tiling looks for each token's matches in the other submission. Without it, 300
     * solutions of 19 KB were priced at 7 s and took over 120: a pair there measured 21-29 ms on one of
     * four threads against 0.2 ms priced. With it, such a pair is about 26 ms, and an ordinary one of
     * 0.8 KB about 0.12 ms against 0.07 ms measured - generous, as the estimate should be.
     */
    override fun pairNanos(bytesA: Int, bytesB: Int): Long = 70_000L + 4L * (bytesA + bytesB) + bytesA.toLong() * bytesB / 14

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

    /** Whether a language's frontend can run in this JVM, replaceable only by a test: see [comparesAs]. */
    internal var available: (SimilarityLanguage) -> Boolean = { it.frontend.available }

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
     * [language], or text if its frontend cannot run in this JVM - Java's needs javac, which a JRE may
     * leave out. A word-level comparison is a weaker answer, and the response names it as text so the
     * page says so, but it is an answer; refusing would leave the teacher with none.
     */
    override fun comparesAs(language: SimilarityLanguage): SimilarityLanguage {
        // Not logged here: this is asked on every bind and every settings read, and the startup warning
        // already names each language this JVM cannot tokenise.
        if (available(language)) return language
        return SimilarityLanguage.TEXT
    }

    override fun open(request: ProviderRequest, run: RunContext): ProviderSession = Session(request, run)

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


    /** One cohort: its tokens, JPlag's submission set built from them, and the tiling the pairs share. */
    private inner class Session(private val request: ProviderRequest, private val run: RunContext) : ProviderSession {
        private val sources = request.sources
        private val files = sources.map { File("submission-${it.id}") }
        private val idByName = sources.associate { it.id.toString() to it.id }
        private val solutionById = sources.associate { it.id to it.solution }

        private lateinit var set: SubmissionSet
        private lateinit var options: JPlagOptions
        private lateinit var submissionById: Map<Long, Submission>
        private lateinit var gst: GreedyStringTiling

        /** Token types as small numbers, for [TokenRuns]; one session's own, so the numbers stay small. */
        private val typeIds = Numbering<TokenType>()

        /** Its quick bound is [bound] itself: nothing tighter to compute for a pair once it is reached. */
        override val quickBoundIsExact = true

        /**
         * Built on a submission's first [bound], as edit distance's profiles are: only the optimised mode reads
         * them, and at the cap they are some 12 bytes a token that a full run would have held for nothing.
         */
        private val runsById = ConcurrentHashMap<Long, TokenRuns>()

        private fun runs(id: Long): TokenRuns = runsById.computeIfAbsent(id) {
            val tokens = submissionById.getValue(it).tokenList
            val ids = IntArray(tokens.size) { i ->
                val type = tokens[i].type
                if (type.isExcludedFromMatching()) -1 else typeIds.of(type)
            }
            TokenRuns.of(ids, options.minimumTokenMatch())
        }

        override fun prepare(): PreparedCohort {
            val template = request.template?.takeIf { it.isNotBlank() }
            // Tokenising cannot stop part way with anything to show, so a cohort it would spend the budget on
            // is refused at its pace, not at the deadline: the estimate prices it from a model of one
            // machine's speed, and on a slower server - or with solutions slower to parse than their size
            // says - a run spent the whole budget here and found nothing.
            // Each solution counted at what the cost model prices it, by the pace and by the calibration both.
            val predicted = LongArray(sources.size) { prepareNanos(utf8Length(sources[it].solution)) }
            fun pace() = if (!run.project) null else RunPace(run.deadline, run.budgetSec, predicted.sum(), "tokenising", run.tooCostlyPreparing)
            var pace = pace()
            // The template too, when there is one, on the pool like everything else: it is parsed inside
            // the permit, cannot be interrupted, and needs the pool's stack as much as a submission does.
            fun tokenise(language: SimilarityLanguage, indices: IntRange, withTemplate: Boolean) = run.workers.all(
                indices.map { i ->
                    {
                        run.preparing(language, predicted[i]) { tokeniseOne("Submission ${sources[i].id}", files[i], sources[i].solution, language, run) }
                            .also { pace?.done(predicted[i]) }
                    }
                } + listOfNotNull(template?.takeIf { withTemplate }?.let { { tokeniseOne("The exercise template", BASE_CODE, it, language, run) } }),
            )

            // A sample first, so a cohort that is not in its language at all - an essay exercise that kept
            // `lahendus.py` - is not parsed in full only to be thrown away: that pass cost twice what the
            // text comparison did, inside the same budget, and could time out a run text alone finishes.
            // The whole cohort still decides when the sample does not. Never where an admin has switched
            // text off: then the cohort is compared in its language, and what does not parse is skipped.
            var language = request.language
            val mayFallBack = language != SimilarityLanguage.TEXT && request.textAllowed
            val sample = minOf(sources.size, LANGUAGE_SAMPLE)
            val sampled = tokenise(language, 0 until sample, withTemplate = false)
            var results = if (mayFallBack && mostlyNotIn(sampled)) sampled
            else sampled + tokenise(language, sample until sources.size, withTemplate = true)
            if (mayFallBack && mostlyNotIn(results.take(sources.size))) {
                log.info { "Most of this cohort did not parse as ${language.wireName}; comparing it as text instead" }
                language = SimilarityLanguage.TEXT
                // Tokenised again, from the start: a pace of its own, read against the time now left.
                pace = pace()
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
            if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)

            // Starter code every submission was built on, when the caller has some. Subtracted as base
            // code, it is not reported as similarity, so a fill-in-the-blank exercise does not flag its
            // whole cohort. The endpoint passes none today; see where TeacherCheckSimilarity calls this.
            val baseCodeFile = template?.let { usableTemplate(results.last(), minTokenMatch) }?.let { tokens ->
                tokensByFile[BASE_CODE] = tokens
                BASE_CODE
            }

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
            options = JPlagOptions(jplagLanguage, emptySet(), emptySet()).withMinimumTokenMatch(minTokenMatch)

            // Parses every submission through PretokenisedLanguage and assigns each a real state.
            set = SubmissionSet(submissions, base, options)
            submissionById = set.submissions.associateBy { idByName.getValue(it.name) }

            // JPlag's own verdict on what could not be used, rather than a hand-rolled re-implementation
            // of rules it already applies. Base code is not in this list; it is handled separately above.
            // The *reason* is translated rather than passed through — see [skipReason].
            val skipped = refused + set.invalidSubmissions.mapNotNull { invalid ->
                idByName[invalid.name]?.let { SkippedSubmission(it, skipReason(invalid.state)) }
            }

            // Identical here is the same tokens *and* the same text once layout is set aside: Python's and
            // Java's token types leave out operators, literals and names, so a max, a min and a sum over a
            // list are one token stream, and grouping on that alone called them identical code. Found by a
            // fingerprint of the types, and by the types themselves only within one: keyed on the types,
            // grouping held a second copy of every token list in the cohort while it ran.
            return PreparedCohort(
                language = language,
                usable = set.submissions.map { idByName.getValue(it.name) },
                skipped = skipped,
                groupKey = { fingerprint(submissionById.getValue(it).tokenList) },
                identical = { a, b ->
                    sameTypes(submissionById.getValue(a).tokenList, submissionById.getValue(b).tokenList) &&
                            sameText(solutionById.getValue(a), solutionById.getValue(b))
                },
            )
        }

        override fun ready(representatives: List<Long>) {
            gst = GreedyStringTiling(options, TokenSequenceMapper(set))
            if (!set.hasBaseCode()) return
            // One submission at a time, and before any pair: JPlag documents generateBaseCodeMarking as
            // not thread-safe, and every comparison reads the markings it leaves. Each is a tiling of its
            // own, against the template, so the deadline is read before each as it is before each pair.
            representatives.forEach {
                if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)
                val submission = submissionById.getValue(it)
                submission.baseCodeComparison = gst.generateBaseCodeMarking(submission, set.baseCode)
            }
        }

        /**
         * One tiling. [PairScore.score] is maximalSimilarity, max(similarityOfFirst, similarityOfSecond),
         * and [PairScore.similarity] their mediant, so the score is always the larger of the two; the rank
         * is the matched tokens, how much code the two share rather than what share of either it is. The
         * pair is oriented as the tiling compared it - the smaller submission first.
         */
        override fun score(a: Long, b: Long): PairScore {
            val comparison = comparePair(gst, submissionById.getValue(a), submissionById.getValue(b))
            return PairScore(
                first = idByName.getValue(comparison.firstSubmission().name),
                second = idByName.getValue(comparison.secondSubmission().name),
                score = comparison.maximalSimilarity(),
                rank = comparison.matches().sumOf { it.minimumLength() }.toDouble(),
                similarity = comparison.similarity(),
                handle = comparison,
            )
        }

        /**
         * The matched tokens' bound, and the shares it allows over JPlag's own divisors - each submission's
         * tokens less its file end and what the starter code marked, read after [ready] has marked it - in
         * the arithmetic [JPlagComparison] uses, so none is below the tiling's. A divisor of none would make
         * JPlag's share NaN; the bound then allows anything.
         */
        override fun bound(a: Long, b: Long): PairBound {
            val matched = runs(a).matchedBound(runs(b)).toDouble()
            val divisorA = submissionById.getValue(a).similarityDivisor
            val divisorB = submissionById.getValue(b).similarityDivisor
            val score = if (minOf(divisorA, divisorB) <= 0) 1.0 else minOf(1.0, matched / minOf(divisorA, divisorB))
            val similarity = if (divisorA + divisorB <= 0) 1.0 else minOf(1.0, 2 * matched / (divisorA + divisorB))
            return PairBound(score = score, rank = matched, similarity = similarity)
        }

        override fun metrics(pair: PairScore): List<SimilarityMetric> {
            val comparison = pair.handle as JPlagComparison
            return listOf(
                SimilarityMetric("similarity", comparison.similarity(), MetricUnit.SHARE),
                SimilarityMetric("max_similarity", comparison.maximalSimilarity(), MetricUnit.SHARE),
                SimilarityMetric("shared_tokens", pair.rank, MetricUnit.COUNT),
            )
        }

        /**
         * A group's members are token for token the same, so the tiling's matches index any member's
         * tokens alike, and the lines are read from the members shown.
         */
        override fun details(pair: PairScore, shownFirst: Long, shownSecond: Long): List<SimilarityMatch> {
            val comparison = pair.handle as JPlagComparison
            val tokens1 = submissionById.getValue(shownFirst).tokenList
            val tokens2 = submissionById.getValue(shownSecond).tokenList
            return comparison.matches().map { match ->
                SimilarityMatch(
                    lineAt(tokens1, match.startOfFirst()),
                    endLineAt(tokens1, match.startOfFirst() + match.lengthOfFirst() - 1),
                    lineAt(tokens2, match.startOfSecond()),
                    endLineAt(tokens2, match.startOfSecond() + match.lengthOfSecond() - 1),
                    match.minimumLength(),
                )
            }
        }
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
     * provider's fields are read-only once constructed, and the frontends' shared state - Python's
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
        run: RunContext,
    ): TokeniseResult {
        if (run.deadline.passed()) throw SimilarityTimedOut(run.budgetSec)

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
     * `TOO_REPETITIVE` and `TOO_SMALL` that [Session.prepare] assigns itself - the last before JPlag would, since
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
        const val ID = "jplag"

        /** How many solutions decide alone that a cohort is not in its language; see [Session.prepare]. */
        private const val LANGUAGE_SAMPLE = 20

        private val LAYOUT = Regex("\\s+")

        /** The template's identifier, beside the submissions'. */
        private val BASE_CODE = File("basecode")
    }
}
