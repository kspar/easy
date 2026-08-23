package core.ems.service.exercise

import core.db.ExerciseVer
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import core.testing.list
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Re-rendering exercise HTML from its Markdown without authoring anything.
 *
 * This rewrites, in bulk, the text students read. Four properties make that safe rather than
 * alarming, and every one of them is the kind that would be silently untrue:
 *
 * 1. **No version is created**, and nothing about authorship moves. A renderer improvement is not an
 *    edit by the teacher. Asserted by counting `exercise_version` rows either side and by comparing
 *    `author`, `valid_from` and `text_md` before and after.
 * 2. **Only the current version.** An older row's HTML records what that version rendered; rewriting
 *    it would be editing history to say something that was never true.
 * 3. **Identical output writes nothing.** Without this the changed count is noise, and the dry run —
 *    the only thing standing between an admin and a thousand rewritten rows — stops being readable.
 * 4. **A row that cannot be rendered keeps the HTML it has.** The failure to avoid is an exercise
 *    left with no text at all, which is worse than text rendered by an old renderer.
 *
 * The motivating case is EZ-1732: maths reached the renderer and none of the stored HTML, so the last
 * test here is a formula going from raw dollars to something KaTeX can typeset.
 */
@IntegrationTest
class AdminRerenderMarkdownTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val controller: AdminRerenderMarkdownController,
) {
    private val api = HttpApi(mockMvc)
    private val admin = Auth.ADMIN_ID
    private val teacher = Auth.TEACHER_ID

    private val endpoint = "/v2/admin/exercises/markdown/rerender"

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin)
            Fixtures.teacher(teacher)
        }
    }

    /** An exercise whose current version carries [md] as source and [html] as stored rendering. */
    private fun exerciseWith(md: String?, html: String?): Long {
        val id = transaction { Fixtures.exercise("Rerender me", teacher) }
        transaction {
            ExerciseVer.update({ (ExerciseVer.exercise eq id) and ExerciseVer.validTo.isNull() }) {
                it[textMd] = md
                it[textHtml] = html
            }
        }
        return id
    }

    private fun currentHtml(exerciseId: Long): String? = transaction {
        ExerciseVer.select(ExerciseVer.textHtml)
            .where { (ExerciseVer.exercise eq exerciseId) and ExerciseVer.validTo.isNull() }
            .single()[ExerciseVer.textHtml]
    }

    private fun versionCount(): Long = transaction { ExerciseVer.selectAll().count() }

    private fun run(apply: Boolean, ids: List<Long>? = null) = api.post(
        endpoint,
        api.body("apply" to apply, "exercise_ids" to ids),
        Auth.asAdmin(admin),
    )

    // --- 1. the dry run writes nothing --------------------------------------------------------------

    @Test
    fun `a dry run reports the change and leaves the stored html alone`() {
        val id = exerciseWith(md = "# Heading", html = "<p>stale</p>")

        val resp = run(apply = false, ids = listOf(id))
        assertEquals(200, resp.status) { resp.body }
        assertEquals("false", resp.field("applied"))
        assertEquals("1", resp.field("changed"))

        // The whole point of a rehearsal.
        assertEquals("<p>stale</p>", currentHtml(id))
    }

    // --- 2. applying rewrites html and nothing else -------------------------------------------------

    @Test
    fun `applying rewrites the html in place, creating no version and moving no attribution`() {
        val id = exerciseWith(md = "# Heading", html = "<p>stale</p>")

        val before = transaction {
            ExerciseVer.select(ExerciseVer.author, ExerciseVer.validFrom, ExerciseVer.textMd)
                .where { (ExerciseVer.exercise eq id) and ExerciseVer.validTo.isNull() }
                .single()
                .let { Triple(it[ExerciseVer.author], it[ExerciseVer.validFrom], it[ExerciseVer.textMd]) }
        }
        val versionsBefore = versionCount()

        val resp = run(apply = true, ids = listOf(id))
        assertEquals(200, resp.status) { resp.body }
        assertEquals("true", resp.field("applied"))
        assertEquals("1", resp.field("changed"))

        assertTrue(currentHtml(id)!!.contains("<h1>")) { "expected rendered Markdown, got ${currentHtml(id)}" }

        // The three claims that make this not-an-edit.
        assertEquals(versionsBefore, versionCount())
        val after = transaction {
            ExerciseVer.select(ExerciseVer.author, ExerciseVer.validFrom, ExerciseVer.textMd)
                .where { (ExerciseVer.exercise eq id) and ExerciseVer.validTo.isNull() }
                .single()
                .let { Triple(it[ExerciseVer.author], it[ExerciseVer.validFrom], it[ExerciseVer.textMd]) }
        }
        assertEquals(before, after)
    }

    // --- 3. identical output is not a change --------------------------------------------------------

    @Test
    fun `html that already matches its markdown is reported unchanged`() {
        // Rendered by the same service, so byte-identical by construction.
        val id = exerciseWith(md = "# Heading", html = null)
        run(apply = true, ids = listOf(id))
        val settled = currentHtml(id)

        val resp = run(apply = true, ids = listOf(id))
        assertEquals("0", resp.field("changed"))
        assertEquals("1", resp.field("unchanged"))
        assertEquals(settled, currentHtml(id))
    }

    // --- 4. nothing to render from ------------------------------------------------------------------

    @Test
    fun `an exercise with no markdown is skipped, and keeps its html`() {
        // The AsciiDoc-era shape: the stored HTML is the only rendering that exists.
        val id = exerciseWith(md = null, html = "<p>from adoc</p>")

        val resp = run(apply = true, ids = listOf(id))
        assertEquals("1", resp.field("skipped"))
        assertEquals("0", resp.field("changed"))
        assertEquals("<p>from adoc</p>", currentHtml(id)) { "an adoc exercise must never be blanked" }
    }

    // --- 5. the report reconciles -------------------------------------------------------------------

    @Test
    fun `an id that is not a current exercise is reported rather than silently ignored`() {
        val id = exerciseWith(md = "# Heading", html = "<p>stale</p>")

        val resp = run(apply = false, ids = listOf(id, 99999999L))
        assertEquals("1", resp.field("scanned"))
        assertEquals(listOf("99999999"), resp.elements("not_found").map { it.asString() })
    }

    @Test
    fun `the outcome counts sum to scanned`() {
        exerciseWith(md = "# One", html = "<p>stale</p>")
        exerciseWith(md = null, html = "<p>adoc</p>")

        val resp = run(apply = false)
        val n = { name: String -> resp.field(name)!!.toInt() }
        assertEquals(n("scanned"), n("changed") + n("unchanged") + n("failed") + n("skipped"))
    }

    // --- 6. only the current version ----------------------------------------------------------------

    @Test
    fun `a superseded version is left exactly as it was`() {
        val id = exerciseWith(md = "# Current", html = "<p>stale current</p>")

        // Close the current row and open a newer one, as an ordinary save does.
        val oldVersionId = transaction {
            val old = ExerciseVer.select(ExerciseVer.id)
                .where { (ExerciseVer.exercise eq id) and ExerciseVer.validTo.isNull() }
                .single()[ExerciseVer.id].value
            val at = TestClock.next()
            ExerciseVer.update({ ExerciseVer.id eq old }) { it[validTo] = at }
            ExerciseVer.insert {
                it[exercise] = EntityID(id, core.db.Exercise)
                it[author] = EntityID(teacher, core.db.Account)
                it[validFrom] = at
                it[graderType] = core.db.GraderType.TEACHER
                it[title] = "Rerender me"
                it[textMd] = "# Newer"
                it[textHtml] = "<p>stale newer</p>"
                it[solutionFileName] = "solution.py"
                it[solutionFileType] = core.db.SolutionFileType.TEXT_EDITOR
            }
            old
        }

        run(apply = true, ids = listOf(id))

        val oldHtml = transaction {
            ExerciseVer.select(ExerciseVer.textHtml).where { ExerciseVer.id eq oldVersionId }
                .single()[ExerciseVer.textHtml]
        }
        assertEquals("<p>stale current</p>", oldHtml) { "history must not be rewritten" }
        assertTrue(currentHtml(id)!!.contains("Newer"))
    }

    /**
     * Driven through `rerenderWithin` rather than the endpoint, because one request scans and writes
     * with nothing in between — the interleaving this guards against needs a save *during* a long
     * run, which the endpoint cannot be made to produce.
     */
    @Test
    fun `a version superseded mid-run is skipped rather than rewritten`() {
        val id = exerciseWith(md = "# Heading", html = "<p>stale</p>")
        val versionId = transaction {
            ExerciseVer.select(ExerciseVer.id)
                .where { (ExerciseVer.exercise eq id) and ExerciseVer.validTo.isNull() }
                .single()[ExerciseVer.id].value
        }
        transaction {
            ExerciseVer.update({ ExerciseVer.id eq versionId }) { it[validTo] = TestClock.next() }
        }

        val stale = AdminRerenderMarkdownController.Target(exerciseId = id, versionId = versionId)
        val result = transaction { controller.rerenderWithin(stale, apply = true) }

        assertEquals(AdminRerenderMarkdownController.Outcome.SKIPPED, result.outcome)
        assertTrue(result.detail!!.contains("saved while this run was in progress"))
        assertEquals("<p>stale</p>", transaction {
            ExerciseVer.select(ExerciseVer.textHtml).where { ExerciseVer.id eq versionId }
                .single()[ExerciseVer.textHtml]
        })
    }

    // --- 7. admin only ------------------------------------------------------------------------------

    @Test
    fun `a teacher cannot re-render anything`() {
        val id = exerciseWith(md = "# Heading", html = "<p>stale</p>")
        assertEquals(403, api.post(endpoint, api.body("apply" to true), Auth.asTeacher(teacher)).status)
        assertEquals("<p>stale</p>", currentHtml(id))
    }

    // --- 8. the reason this exists ------------------------------------------------------------------

    @Test
    fun `a formula stored before maths existed becomes typesettable`() {
        // Verbatim shape from the migrated corpus: dollars wrapping a code span (EZ-1732).
        val id = exerciseWith(
            md = "Valem: \$`(kaal) = (pikkus)^3`\$",
            html = "<p>Valem: \$`(kaal) = (pikkus)^3`\$</p>",
        )

        assertEquals("1", run(apply = true, ids = listOf(id)).field("changed"))

        val html = currentHtml(id)!!
        assertTrue(html.contains("data-easy-tex")) { "no math marker for KaTeX to find in: $html" }
        // The backticks were the migration's wrapper, not TeX.
        assertTrue(html.contains("(kaal) = (pikkus)^3")) { html }
        assertNotEquals(true, html.contains("<code>")) { "the wrapper became a code span again: $html" }
    }

    @Test
    fun `an exercise with no maths is not disturbed by the math extension`() {
        val id = exerciseWith(md = "Costs \$5 and \$6 today.", html = "<p>stale</p>")
        run(apply = true, ids = listOf(id))

        val html = currentHtml(id)!!
        assertTrue(html.contains("\$5")) { html }
        assertNull(html.takeIf { it.contains("data-easy-tex") }) { "currency became maths: $html" }
    }
}
