package core.ems.service.exercise

import core.db.TeacherInlineComment
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Inline comments, and specifically that `type` is a closed set on both sides of the wire.
 *
 * **This is EZ-1777.** The field was a bare `String` on the request, in the column and on the
 * response, stored verbatim and echoed back, while `web/src/api/types.ts` declared
 * `'comment' | 'suggestion'` — so the client's type was a promise core did not keep, and a teacher
 * could `POST` `type: "banana"` and have core serve it back forever.
 *
 * It was found by the `api-types-contract` check rather than by anything failing, and it was inert:
 * the UI writes the field and never reads it, branching on `suggested_code` instead. That is worth
 * remembering when reading these tests, because it is the reason they are about the *boundary*
 * rather than about rendering. Nothing renders `type` today. The bug was that the first person to
 * write `switch (c.type)` would have inherited a hole invisible in the type system.
 *
 * The two negative tests are the substance. The first is the obvious one — junk is refused. The
 * second is the one that would have been easy to leave out: the **old lowercase spelling** is also
 * refused. Every value the previous client ever sent was `comment` or `suggestion`, so an
 * implementation that accepted those case-insensitively would look maximally compatible and would
 * leave the column with two spellings of the same thing, which is the state changeset `210826-3`
 * exists to end.
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

    private fun createBody(type: Any?, suggestedCode: String? = null) = api.body(
        buildMap<String, Any?> {
            put("line_start", 3)
            put("line_end", 3)
            put("code", "print(a + b)")
            put("text_md", "Think about `a` and `b` both being 0.")
            put("type", type)
            if (suggestedCode != null) put("suggested_code", suggestedCode)
        }
    )

    private fun post(type: Any?, suggestedCode: String? = null) = api.post(
        "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/inline-comments",
        createBody(type, suggestedCode),
        Auth.asTeacher(teacher),
    )

    // `orderBy` because the assertions below compare against an ordered list, and `selectAll()`
    // promises no order at all — a plan change would flip two rows and read as a defect.
    private fun storedTypes(): List<String> = transaction {
        TeacherInlineComment.selectAll()
            .orderBy(TeacherInlineComment.id, SortOrder.ASC)
            .map { it[TeacherInlineComment.type].name }
    }

    @Test
    fun `a comment keeps its type through the teacher's read and the student's`() {
        val comment = post("COMMENT")
        assertEquals(201, comment.status) { comment.body }
        assertEquals("COMMENT", comment.field("type"))

        val suggestion = post("SUGGESTION", suggestedCode = "print(a + b + 1)")
        assertEquals(201, suggestion.status) { suggestion.body }
        assertEquals("SUGGESTION", suggestion.field("type"))

        // Both reads go through selectInlineComments, so this is one code path seen from two roles —
        // but a student read that 500s on the column is the failure this fix could plausibly have
        // introduced, and it would be invisible from the teacher side.
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

        // Asserting on the *order* is only legitimate because `selectInlineComments` now breaks the
        // `created_at` tie with the id. Before that, two POSTs landing in the same millisecond
        // decided whether this test passed — EZ-1763's shape again, found by reviewing this fix.
        val expected = listOf("COMMENT", "SUGGESTION")
        assertEquals(expected, asTeacher.elements("inline_comments").map { it.get("type").asString() })
        assertEquals(expected, asStudent.elements("inline_comments").map { it.get("type").asString() })
        assertEquals(expected, storedTypes())
    }

    @Test
    fun `an update can turn a comment into a suggestion`() {
        val created = post("COMMENT")
        val commentId = created.field("id")
        assertNotNull(commentId) { "Could not create the comment: ${created.body}" }

        val updated = api.put(
            "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/inline-comments/$commentId",
            createBody("SUGGESTION", suggestedCode = "print(a + b)"),
            Auth.asTeacher(teacher),
        )

        assertEquals(200, updated.status) { updated.body }
        assertEquals("SUGGESTION", updated.field("type"))
        // The response is built from the request rather than re-read from the row, so asserting on
        // the response alone would pass even if the update wrote nothing.
        assertEquals(listOf("SUGGESTION"), storedTypes())
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

    @Test
    fun `a type outside the enum is refused, and nothing is stored`() {
        val resp = post("banana")

        assertEquals(400, resp.status) { resp.body }
        assertEquals("INVALID_PARAMETER_VALUE", resp.errorCode)

        // The accepted values have to reach the caller. Jackson's own message carries them, which is
        // why the handler passes originalMessage through rather than replacing it with something
        // tidier — asserting on the two names rather than on the wording, so a Jackson upgrade that
        // rephrases the sentence does not fail this.
        val msg = resp.jsonOrNull?.get("log_msg")?.asString().orEmpty()
        assertTrue(msg.contains("COMMENT") && msg.contains("SUGGESTION")) {
            "The 400 did not name the accepted values, so a client cannot tell what to send: $msg"
        }

        assertTrue(storedTypes().isEmpty()) { "A refused request still wrote a row: ${storedTypes()}" }
    }

    @Test
    fun `the old lowercase spelling is refused rather than quietly accepted`() {
        // `comment` and `suggestion` are exactly what every client sent before EZ-1777, so refusing
        // them is a decision worth pinning rather than an accident.
        //
        // Note what the decision is *not* about: Jackson's ACCEPT_CASE_INSENSITIVE_ENUMS would map
        // `comment` to COMMENT and Exposed would still write `COMMENT`, so leniency here could not
        // have put two spellings in the column. The reasons are that the feature is a MapperFeature
        // — global, so it would loosen all 32 enum-typed fields on this API to buy one field a
        // migration window — and that the window it buys is a client this repo replaced in the same
        // commit, on a version that has not shipped.
        //
        // The cost, said out loud: core and web are separate artifacts, so between the two deploys a
        // teacher holding the previous bundle gets a 400 where a save used to work. On dev that is
        // the minute between two autodeploys of the same commit; in production it is nothing at all,
        // because teacher_inline_comment does not exist in the released schema.
        listOf("comment", "suggestion", "Comment").forEach { spelling ->
            val resp = post(spelling)
            assertEquals(400, resp.status) { "'$spelling' was accepted: ${resp.body}" }
        }
        assertTrue(storedTypes().isEmpty()) { "One of the lowercase spellings wrote a row." }
    }

    @Test
    fun `a missing type is refused`() {
        // `required = true` on the JsonProperty is not what enforces this — Jackson's Kotlin module
        // refuses a null for a non-nullable constructor parameter, which is a different failure with
        // a different message. Worth pinning either way: the field is the discriminator.
        val resp = post(null)

        assertEquals(400, resp.status) { resp.body }
        assertTrue(storedTypes().isEmpty())
    }
}
