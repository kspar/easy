package core.ems.service.exercise

import core.db.TeacherInlineComment
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import core.testing.nullableText
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Inline comments, and specifically that `suggested_code` is the only thing that distinguishes a
 * suggestion from a comment.
 *
 * **This is EZ-1777, after it changed its mind.** There was a `type` field — `'comment'` or
 * `'suggestion'` — on the request, in the column and on the response. Core took it as an
 * unvalidated `String`, stored it verbatim and echoed it back, while `web/src/api/types.ts`
 * declared a closed union, so a teacher could `POST` `"banana"` and have core serve it forever. The
 * first fix made it a Kotlin enum. The right fix, one commit later, was to delete it: the editor
 * computed it as `suggestedCode ? 'suggestion' : 'comment'` on every save and no reader anywhere
 * consulted it, so the field was a second statement of `suggested_code IS NOT NULL` that could
 * disagree with the first.
 *
 * What that leaves worth testing is the distinction itself, which is now single-sourced, plus two
 * properties of having removed a wire field:
 *
 * - a client still sending `type` is **unaffected**, because `FAIL_ON_UNKNOWN_PROPERTIES` is off and
 *   the field carried no information. That is the opposite call from `legacy_content_fields.kt`,
 *   which rejects removed `*_adoc` fields precisely because ignoring *those* silently produced an
 *   empty exercise. The distinguishing question is whether the ignored field held anything;
 * - clearing a suggestion's body turns it back into a comment. This is the case an earlier version
 *   of the note on `InlineCommentType` claimed the enum was protecting, wrongly — it is reachable,
 *   and it is reachable through `suggested_code` alone.
 */
@IntegrationTest
class InlineCommentApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID

    private var courseId = 0L
    private var courseExId = 0L
    private var submissionId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            courseId = Fixtures.course("Inline comments")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, student)
            val exerciseId = Fixtures.exercise("Sum of two numbers", teacher)
            courseExId = Fixtures.courseExercise(courseId, exerciseId)
            submissionId = Fixtures.submission(courseExId, student, number = 1)
        }
    }

    /** [extra] is for fields the DTO does not declare — see the `type` test below. */
    private fun body(suggestedCode: String? = null, extra: Map<String, Any?> = emptyMap()) = api.body(
        buildMap<String, Any?> {
            put("line_start", 3)
            put("line_end", 3)
            put("code", "print(a + b)")
            put("text_md", "Think about `a` and `b` both being 0.")
            if (suggestedCode != null) put("suggested_code", suggestedCode)
            putAll(extra)
        }
    )

    private fun post(suggestedCode: String? = null, extra: Map<String, Any?> = emptyMap()) = api.post(
        "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/inline-comments",
        body(suggestedCode, extra),
        Auth.asTeacher(teacher),
    )

    private fun put(commentId: String, suggestedCode: String? = null) = api.put(
        "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/inline-comments/$commentId",
        body(suggestedCode),
        Auth.asTeacher(teacher),
    )

    // `orderBy` because the assertions compare against an ordered list, and `selectAll()` promises
    // no order at all — a plan change would flip two rows and read as a defect.
    private fun storedSuggestions(): List<String?> = transaction {
        TeacherInlineComment.selectAll()
            .orderBy(TeacherInlineComment.id, SortOrder.ASC)
            .map { it[TeacherInlineComment.suggestedCode] }
    }

    @Test
    fun `a comment and a suggestion survive the teacher's read and the student's`() {
        val comment = post()
        assertEquals(201, comment.status) { comment.body }
        assertNull(comment.nullableField("suggested_code"))

        val suggestion = post(suggestedCode = "print(a + b + 1)")
        assertEquals(201, suggestion.status) { suggestion.body }
        assertEquals("print(a + b + 1)", suggestion.nullableField("suggested_code"))

        // Both reads go through selectInlineComments, so this is one code path seen from two roles —
        // but a student read that fails on the column is invisible from the teacher side, and this
        // is a column the previous commit changed the type of.
        val asTeacher = api.get(
            "/v2/teacher/courses/$courseId/exercises/$courseExId/students/$student/inline-comments",
            Auth.asTeacher(teacher),
        )
        val asStudent = api.get(
            "/v2/student/courses/$courseId/exercises/$courseExId/inline-comments",
            Auth.asStudent(student),
        )
        assertEquals(200, asTeacher.status) { asTeacher.body }
        assertEquals(200, asStudent.status) { asStudent.body }

        // Asserting on the *order* is only legitimate because `selectInlineComments` breaks the
        // `created_at` tie with the id. Before that, two POSTs landing in the same millisecond
        // decided whether this test passed — EZ-1763's shape again, found by reviewing this work.
        val expected = listOf(null, "print(a + b + 1)")
        assertEquals(expected, asTeacher.elements("inline_comments").map { it.nullableText("suggested_code") })
        assertEquals(expected, asStudent.elements("inline_comments").map { it.nullableText("suggested_code") })
        assertEquals(expected, storedSuggestions())
    }

    @Test
    fun `the response carries no type field at all`() {
        val created = post(suggestedCode = "print(a + b)")

        // The status assertions are not ceremony. Without them both checks below pass over a dead
        // endpoint: a 400 leaves an error body with no `type` in it, and a 403 on the read leaves an
        // empty element list that satisfies `all {}` vacuously. Caught in review, and it is the
        // failure family this log has a whole section about — write the positive case in.
        assertEquals(201, created.status) { created.body }
        assertNull(created.jsonOrNull?.get("type")) { "`type` is back on the response: ${created.body}" }

        val read = api.get(
            "/v2/student/courses/$courseId/exercises/$courseExId/inline-comments",
            Auth.asStudent(student),
        )
        assertEquals(200, read.status) { read.body }

        val comments = read.elements("inline_comments")
        assertEquals(1, comments.size) { "Nothing to check `type`'s absence on: ${read.body}" }
        assertTrue(comments.all { it.get("type") == null }) { "`type` is back on the read path: ${read.body}" }
    }

    @Test
    fun `an update adds a suggestion body, and clearing it makes the comment plain again`() {
        val created = post()
        val commentId = created.field("id")
        assertNotNull(commentId) { "Could not create the comment: ${created.body}" }

        val withSuggestion = put(commentId!!, suggestedCode = "print(a + b)")
        assertEquals(200, withSuggestion.status) { withSuggestion.body }
        assertEquals("print(a + b)", withSuggestion.nullableField("suggested_code"))
        // The response is built from the request rather than re-read from the row, so asserting on
        // the response alone would pass even if the update wrote nothing.
        assertEquals(listOf("print(a + b)"), storedSuggestions())

        // Omitting the field is how the client says "no longer a suggestion" — it never sends an
        // empty string. With `type` gone this is the whole of that transition, so it is the one
        // update worth pinning.
        val cleared = put(commentId)
        assertEquals(200, cleared.status) { cleared.body }
        assertNull(cleared.nullableField("suggested_code"))
        assertEquals(listOf<String?>(null), storedSuggestions())
    }

    @Test
    fun `an empty suggested_code is stored as no suggestion at all`() {
        // The third state. `suggested_code IS NOT NULL` is now the definition of a suggestion, so an
        // empty string stored as-is would be a suggestion by that rule and a plain comment to every
        // render site in the app — one row with two answers, which is what removing `type` was for.
        // The client cannot send it today; that is not a reason for the API to accept it.
        val created = post(suggestedCode = "")
        assertEquals(201, created.status) { created.body }
        assertNull(created.nullableField("suggested_code")) { "An empty suggestion came back: ${created.body}" }
        assertEquals(listOf<String?>(null), storedSuggestions())

        // And on the way through an update, which is the path that would let a saved suggestion decay
        // into the third state rather than back into a comment.
        val updated = api.put(
            "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/inline-comments/${created.field("id")}",
            body(suggestedCode = ""),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, updated.status) { updated.body }
        assertNull(updated.nullableField("suggested_code"))
        assertEquals(listOf<String?>(null), storedSuggestions())
    }

    @Test
    fun `a client still sending the removed type field is unaffected`() {
        // `FAIL_ON_UNKNOWN_PROPERTIES` is off, so this is a 201 rather than a 400 — deliberately.
        // `type` was derived from `suggested_code` on the client, so a request carrying both cannot
        // lose anything by having one ignored, which is why it is not on the
        // `rejectLegacyContentFields` list next door. If that ever stops being true, this test says
        // so by turning red rather than by a teacher's suggestion quietly becoming a comment.
        val resp = post(suggestedCode = "print(a + b)", extra = mapOf("type" to "suggestion"))

        assertEquals(201, resp.status) { resp.body }
        assertEquals("print(a + b)", resp.nullableField("suggested_code"))
        assertNull(resp.jsonOrNull?.get("type"))
        assertEquals(listOf("print(a + b)"), storedSuggestions())
    }

    /*
     * There is deliberately no test here for the `created_at` tie that `selectInlineComments`'s id
     * tiebreaker exists to settle, and the reason is worth recording rather than leaving as an
     * absence.
     *
     * One was written: two rows inserted with the same instant, then an UPDATE of the older one, on
     * the theory that Postgres writes the new tuple later in the heap and a sequential scan would
     * then return them the wrong way round. It passed **with the tiebreaker and without it** — so it
     * was a detector that could not fire, dressed up with a comment claiming it had been verified.
     * Deleted rather than kept.
     *
     * What is left is a fix with no test, which is the honest state: the wrong answer under a tie is
     * whatever the plan produces, and a plan cannot be made to misbehave on demand from here. The
     * ordering is still worth making total — see the comment at the query.
     */
}
