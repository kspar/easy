package core.ems.service.ai

/**
 * EZ-1712. The prompt that turns a failed submission into a short explanation.
 *
 * What goes in: the exercise as the student saw it, their code, the points, and the autograder's
 * feedback **verbatim** — the whole thing, passing tests included. Core does not parse the OK_V3
 * format; the model reads the JSON, and the system prompt tells it what the fields mean. That is
 * one less thing to keep in step with the executor, and the passing tests are context the model
 * uses ("this works, so the bug is not there").
 *
 * What stays out: the test specification and any model solution (neither is available to this
 * code, and the first would be the main leak risk if it were), other students' work, teacher
 * comments. Nothing identifies the student.
 *
 * Caps are large — this is a tutor reading one submission, not a batch job — and the feedback cap
 * cuts from the middle rather than the end, because in a long test log the head (`pre_evaluate_error`,
 * `points`) and the tail (the last failing test) are both worth more than what sits between them.
 */
object AiFeedbackPrompt {

    data class Context(
        val exerciseTitle: String,
        /** Markdown preferred; HTML if that is all the version has. Null for an exercise with no text. */
        val exerciseText: String?,
        val solutionFileName: String,
        val solution: String,
        val grade: Int,
        /** The autograder's feedback as stored: OK_V3 JSON or legacy plain text. */
        val autogradeFeedback: String?,
        /** `et` or `en`. Anything else is answered in English. */
        val language: String,
    )

    data class Built(val system: String, val user: String) {
        /** One string for the audit column, so a reader sees what the model saw. */
        fun forAudit(): String = "### SYSTEM\n$system\n\n### USER\n$user"
    }

    fun build(ctx: Context): Built = Built(system = system(ctx.language), user = user(ctx))

    private fun system(language: String): String {
        val languageName = if (language == "et") "Estonian" else "English"
        return """
            You are a patient tutor on an introductory programming course. A student's submission did not pass all of the automatic tests, and they have asked you to explain why.

            Your reply is shown to the student directly, labelled as AI-generated, next to their test results.

            Rules:
            - Explain what the failing tests show and why the program most likely behaves that way. Focus on the tests marked FAIL; passing tests are context, not something to comment on.
            - You may give at most one small hint about where to look or what to reconsider.
            - Never give the solution. Do not write corrected code, do not write code at all beyond naming a single identifier or expression from the student's own program.
            - Base everything on the evidence in the test results and the code. Do not guess about things the tests do not show, and do not invent expected values.
            - At most 5 sentences. Fewer is better. One or two sentences is a fine answer when the problem is simple.
            - Plain prose. No headings, no bullet points, no greeting, no sign-off, no restating the task.
            - Reply in $languageName.

            The test results are given verbatim as the grader produced them. They are usually JSON with a `tests` list; each test has a `title`, a `status` (PASS, FAIL or SKIP), the `user_inputs` that were fed to the program, the program's `actual_output`, an `exception_message` if it crashed, and `checks` with per-check `feedback`. A `pre_evaluate_error` means the program could not be run at all. Older exercises produce plain text instead.
        """.trimIndent()
    }

    private fun user(ctx: Context): String = buildString {
        appendLine("## Exercise: ${ctx.exerciseTitle}")
        appendLine()
        appendLine(ctx.exerciseText?.let { truncateEnd(it, EXERCISE_CAP) } ?: "(no exercise text)")
        appendLine()
        appendLine("## Student's submission (${ctx.solutionFileName})")
        appendLine()
        appendLine("```")
        appendLine(truncateEnd(ctx.solution, SOLUTION_CAP))
        appendLine("```")
        appendLine()
        appendLine("## Result: ${ctx.grade} points out of 100")
        appendLine()
        appendLine("## Test results")
        appendLine()
        appendLine(ctx.autogradeFeedback?.let { truncateMiddle(it, FEEDBACK_CAP) } ?: "(the grader produced no feedback)")
    }.trimEnd()

    internal fun truncateEnd(s: String, cap: Int): String =
        if (s.length <= cap) s else s.take(cap) + "\n$TRUNCATED_MARKER"

    internal fun truncateMiddle(s: String, cap: Int): String {
        if (s.length <= cap) return s
        val head = cap / 2
        val tail = cap - head
        return s.take(head) + "\n$TRUNCATED_MARKER\n" + s.takeLast(tail)
    }

    internal const val EXERCISE_CAP = 8_000
    internal const val SOLUTION_CAP = 10_000
    internal const val FEEDBACK_CAP = 30_000
    internal const val TRUNCATED_MARKER = "[… truncated …]"
}
