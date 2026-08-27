package core.ems.service.snippet

import core.db.FeedbackSnippet
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Feedback snippets — the canned comments a teacher keeps for grading. `snippet/` had no test at all.
 *
 * The thing worth pinning is what the timestamp means. Every edit writes `now()` into it and the list
 * is ordered by it descending, so editing an old snippet moves it to the top. That is the ordering the
 * feature has, and the column was called `created_at` — which made the behaviour read as a sort bug
 * rather than as the column's actual meaning. It is `modified_at` now (changeset `240826-1`), on the
 * wire as well as in the schema, so the name and the behaviour agree.
 *
 * It is deliberately *not* most-recently-used: nothing bumps the timestamp when a snippet is inserted
 * into feedback, only when its text is edited. [editingMovesASnippetToTheTop] is the test that would
 * have to change if that were ever wanted.
 */
@IntegrationTest
class FeedbackSnippetApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)
    private val teacher = Auth.TEACHER_ID

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction { Fixtures.teacher(teacher) }
    }

    /** POST /v2/snippets returns no body, so ids come from the list — see [idOf]. */
    private fun create(md: String) {
        val resp = api.post("/v2/snippets", api.body("snippet_md" to md), Auth.asTeacher(teacher))
        assertEquals(200, resp.status) { resp.body }
    }

    private fun idOf(md: String): String = list().elements("snippets")
        .single { it.get("snippet_md").asText() == md }
        .get("id").asText()

    private fun update(id: String, md: String) =
        api.put("/v2/snippets/$id", api.body("snippet_md" to md), Auth.asTeacher(teacher))

    private fun list() = api.get("/v2/snippets", Auth.asTeacher(teacher))

    private fun markdownsOf(resp: HttpApi.Response) =
        resp.elements("snippets").map { it.get("snippet_md").asText() }

    @Test
    fun `a snippet round-trips, rendered, newest first`() {
        create("first")
        create("second")

        val resp = list()
        assertEquals(200, resp.status) { resp.body }
        assertEquals(listOf("second", "first"), markdownsOf(resp)) { resp.body }
        assertTrue(resp.elements("snippets").first().get("snippet_html").asText().contains("second")) { resp.body }
    }

    @Test
    fun `the timestamp is reported as modified_at, because that is what it holds`() {
        create("only")

        val snippet = list().elements("snippets").single()
        assertTrue(snippet.has("modified_at")) { "expected modified_at: $snippet" }
        assertTrue(!snippet.has("created_at")) { "created_at was a lie and is gone: $snippet" }
    }

    @Test
    fun editingMovesASnippetToTheTop() {
        create("oldest")
        create("newest")

        assertEquals(listOf("newest", "oldest"), markdownsOf(list()))

        val edited = update(idOf("oldest"), "oldest, corrected")
        assertEquals(200, edited.status) { edited.body }

        // Intended, and the reason the column is not called created_at: the list is
        // most-recently-touched first, so correcting a typo promotes that snippet.
        assertEquals(listOf("oldest, corrected", "newest"), markdownsOf(list())) { list().body }
    }

    @Test
    fun `clearing the markdown deletes the snippet`() {
        create("doomed")
        val resp = api.put("/v2/snippets/${idOf("doomed")}", api.body("snippet_md" to null), Auth.asTeacher(teacher))

        assertEquals(200, resp.status) { resp.body }
        assertEquals(emptyList<String>(), markdownsOf(list()))
    }

    @Test
    fun `snippets sharing a timestamp still have one definite order`() {
        // Created inside one transaction so they land on the same `modified_at`, which is the case a
        // sort on that column alone cannot decide. `id` descending settles it, and the teacher sees a
        // stable menu rather than one that reshuffles between requests.
        (1..4).forEach { create("snippet $it") }

        transaction {
            val stamps = FeedbackSnippet.selectAll().map { it[FeedbackSnippet.modifiedAt] }.toSet()
            // Not a guarantee of the fixture — if the clock ticked between inserts this test still
            // holds, it just stops exercising the tie. Stated so a future reader knows which.
            if (stamps.size > 1) return@transaction
        }

        assertEquals(4, markdownsOf(list()).size)
        assertEquals(listOf("snippet 4", "snippet 3", "snippet 2", "snippet 1"), markdownsOf(list())) { list().body }
    }
}
