package core.ems.service.exercise

import core.db.Account
import core.db.Exercise
import core.db.TeacherSubmission
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * `GET /v2/exercises/{id}/testing/autoassess/submissions` — the teacher's own test submissions for an
 * exercise, and the two things about it that were wrong. The endpoint had no test at all.
 *
 * `count` was `submissions.size`, which equals the total only when nothing is paginated — so it was
 * wrong in exactly the case a `count` field exists for, and a client fetching a page could not learn
 * how many rows there were. The `COUNT(*)` was already being run on every request and discarded.
 *
 * And the sort was `created_at DESC` alone, with no tiebreaker. A teacher testing an exercise submits
 * in bursts, so rows share a timestamp; rows tied on the whole sort key may come back in any order,
 * and nothing requires that order to be the same for `offset 0` as for `offset 2`. Paginating over
 * that silently loses rows and repeats others.
 *
 * The fixture leans on that on purpose: [tiedTimestamp] is used for several submissions, so a sort
 * that is not total has something to be unstable about. `TestClock.fixed` exists for asking for a
 * collision deliberately rather than hoping for one.
 */
@IntegrationTest
class TeacherTestSubmissionsApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val otherTeacher = "other-teacher"

    private var exerciseId = 0L

    /** All five submissions share this, so `created_at` alone cannot order them. */
    private val tiedTimestamp = TestClock.fixed(5)

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.teacher(otherTeacher)
            exerciseId = Fixtures.exercise("Exercise under test", teacher)

            repeat(5) { n -> submission(teacher, "solution $n") }
            // Another teacher's test submission on the same exercise, which must never appear.
            submission(otherTeacher, "not mine")
        }
    }

    private var nextId = 1L

    /**
     * Assigns `id` explicitly, which no production code does and no other fixture here needs to.
     *
     * `teacher_submission.id` has **no default in the test database**, so the `insertAndGetId` that
     * `TeacherAutoassess` itself uses fails here with "null value in column id". It works in a
     * deployed environment, where the column is `nextval('teacher_submission_id_seq')` — so this is
     * the test schema disagreeing with the real one, not a bug in the caller. The changeset adds the
     * column as `bigserial` (v2.xml, `160320-1`), which Liquibase does not always turn into a sequence
     * when it arrives via `addColumn` rather than `createTable`; the deployed sequence predates it.
     *
     * An explicit id is valid against both schemas, so the tests below say what they mean today
     * without waiting on that. It is also why this endpoint had no test at all: it cannot be reached
     * through its own write path in this database.
     */
    private fun submission(teacherId: String, solution: String) = TeacherSubmission.insertAndGetId {
        it[TeacherSubmission.id] = EntityID(nextId++, TeacherSubmission)
        it[TeacherSubmission.teacher] = EntityID(teacherId, Account)
        it[exercise] = EntityID(exerciseId, Exercise)
        it[createdAt] = tiedTimestamp
        it[TeacherSubmission.solution] = solution
    }.value

    private fun read(query: String = "") =
        api.get("/v2/exercises/$exerciseId/testing/autoassess/submissions$query", Auth.asTeacher(teacher))

    private fun solutionsOf(resp: HttpApi.Response) =
        resp.elements("submissions").map { it.get("solution").asText() }

    @Test
    fun `count is the total, not the size of the page`() {
        val all = read()
        assertEquals(200, all.status) { all.body }
        assertEquals(5, all.field("count")?.toInt()) { all.body }
        assertEquals(5, solutionsOf(all).size) { all.body }

        // The case the field exists for, and the one it used to get wrong: ask for two rows and the
        // count must still say how many there are.
        val page = read("?limit=2")
        assertEquals(200, page.status) { page.body }
        assertEquals(2, solutionsOf(page).size) { page.body }
        assertEquals(5, page.field("count")?.toInt()) { "count reported the page size: ${page.body}" }
    }

    @Test
    fun `rows sharing a timestamp are ordered by id, newest first`() {
        // The tiebreaker, asserted as the order it specifies. All five rows share a `created_at`, so
        // `created_at DESC` alone does not determine this list and the database may return the tied
        // rows in any order it likes; with `id DESC` after it the sort is total and there is exactly
        // one correct answer.
        //
        // Written this way after the obvious version turned out to prove nothing. Walking the list in
        // pages and asserting they partition the set passed *without* the tiebreaker: Postgres happened
        // to return five tied rows in the same order for each query, so the pages lined up. A test that
        // depends on the database declining to exercise its freedom is not a guard.
        assertEquals((4 downTo 0).map { "solution $it" }, solutionsOf(read())) { read().body }
    }

    @Test
    fun `paging visits each row exactly once`() {
        // Not the tiebreaker guard — see above — but still worth having: it is the property a client
        // paginating this endpoint actually depends on, and it fails loudly if `limit`/`offset` are
        // ever wired up wrongly.
        val pages = listOf(read("?limit=2&offset=0"), read("?limit=2&offset=2"), read("?limit=2&offset=4"))
        pages.forEach { assertEquals(200, it.status) { it.body } }

        val seen = pages.flatMap { solutionsOf(it) }
        assertEquals(5, seen.size) { "pages overlapped or dropped rows: $seen" }
        assertEquals((0..4).map { "solution $it" }.toSet(), seen.toSet()) { seen.toString() }
    }

    @Test
    fun `another teacher's test submissions are not mine`() {
        // The control on the fixture: six rows exist on this exercise and five are the caller's, so a
        // count of 5 above is filtering rather than miscounting.
        assertEquals(5, read().field("count")?.toInt())
        assertEquals(false, solutionsOf(read()).contains("not mine"))
    }
}
