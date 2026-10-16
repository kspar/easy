package core.ems.service.code.similarity

import de.jplag.Token
import java.io.File

/**
 * The languages similarity can compare, each with the one [SimilarityFrontend] that tokenises it.
 *
 * Everything that differs between languages is behind the frontend; the comparison, the limits, the
 * permit and the response are one piece of code for all of them. So adding a language is three steps
 * and touches nothing else: a frontend of its own, an entry here, and a line in [of] that recognises
 * its exercises.
 *
 * The resolved value is reported in the API response as [wireName], so the page can say which
 * comparison ran rather than letting a word-level one read like a token-level one.
 */
enum class SimilarityLanguage(val wireName: String, internal val frontend: SimilarityFrontend) {
    PYTHON("python", PythonFrontend),
    JAVA("java", JavaFrontend),
    SQL("sql", SqlFrontend),

    /** Everything else: word by word, which still finds copied prose and copied code, without syntax. */
    TEXT("text", TextFrontend);

    /**
     * Tokens for one solution: [frontend]'s, with its failures sorted into the three kinds the caller
     * treats differently - see [SimilarityFrontend] for the contract.
     *
     * A refusal on cost passes through as itself: the code may be fine, merely too long or too deep to
     * be worth the time, and must not be reported as unparseable. Any other exception is this one
     * submission's parse failure. JPlag's own adapters wrap the same region in `catch (Exception)` for
     * the same reason - error recovery can leave a tree a listener or scanner chokes on - and one
     * unusual submission must cost that submission, not everybody else's comparison.
     *
     * An Error is not an exception here, and is not caught: a StackOverflowError is the caller's to
     * contain, and an OutOfMemoryError must reach the error handler rather than read as a skip (see
     * `SimilarityService.tokeniseOne`). That includes one a parser has itself caught and wrapped -
     * javac reports an Error thrown inside its parser as an IllegalStateException around it - which is
     * why the cause chain is searched before anything is filed as a parse failure.
     */
    fun tokenise(id: File, solution: String, maxTokens: Int): List<Token> = read(id, solution, maxTokens).tokens

    /** [tokenise], and whether the parser had to recover to produce the tokens: see [Tokenised]. */
    internal fun read(id: File, solution: String, maxTokens: Int): Tokenised = guarded(id) { frontend.read(id, solution, maxTokens) }

    companion object {
        /**
         * The language of an exercise's solutions, from the two things the schema says about them.
         *
         * The grader's container image first, where it only ever runs one language, because that one
         * cannot be wrong. The solution file name can: it is teacher-authored, every new exercise
         * starts as `lahendus.py`, and the executor writes every submission as `lahendus.py` whatever
         * the exercise calls it (aae/containers.py) - which is why the Silmused template's first line
         * renames the file to `lahendus.sql`. So an SQL exercise often keeps the default name, and
         * resolved by the name alone its SQL was parsed as Python: error recovery kept a few tokens of
         * each query and the scores were computed over those, with nothing on the page to say so.
         * The file name decides everything else.
         */
        fun of(solutionFileName: String, containerImage: String?): SimilarityLanguage =
            containerImage?.let(::gradedBy) ?: forFileName(solutionFileName)

        /** By extension alone, for an exercise whose grader does not settle it. */
        fun forFileName(fileName: String): SimilarityLanguage =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "py", "pyw" -> PYTHON
                "java" -> JAVA
                "sql" -> SQL
                else -> TEXT
            }

        /**
         * A grader that runs only one language, recognised by its image name without registry, tag or
         * digest. Only the one whose solutions' file names cannot be trusted is listed: Silmused, which
         * checks PostgreSQL (the library's "Silmused PostgreSQL" type,
         * web/src/features/library/autoEvalTypes.ts). The Python graders need no entry, since their
         * exercises keep the `.py` they start with.
         */
        private fun gradedBy(containerImage: String): SimilarityLanguage? =
            when (containerImage.trim().substringAfterLast('/').substringBefore('@').substringBefore(':').lowercase()) {
                "silmused" -> SQL
                else -> null
            }
    }
}

/**
 * Runs one frontend's [read], its failures sorted into the three kinds the caller treats differently;
 * see [SimilarityLanguage.tokenise]. Separate so a test can hand it a frontend that throws, which no real
 * input does reliably.
 */
internal fun guarded(id: File, read: () -> Tokenised): Tokenised = try {
    read()
} catch (e: SolutionRefused) {
    throw e
} catch (e: Exception) {
    virtualMachineErrorIn(e)?.let { throw it }
    throw SimilarityParseException("Could not tokenise submission ${id.name}", e)
}

/**
 * The Error somewhere in [e]'s causes, if a parser caught one and wrapped it. Bounded, because a
 * cause chain is a linked list anything can build, including into a loop.
 */
internal fun virtualMachineErrorIn(e: Throwable): VirtualMachineError? =
    generateSequence(e.cause) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<VirtualMachineError>().firstOrNull()

private const val MAX_CAUSE_DEPTH = 16
