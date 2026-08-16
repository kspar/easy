package core.ems.service.exercise

import core.db.Asset
import core.db.AutoExercise
import core.db.ContainerImage
import core.db.Exercise
import core.db.ExerciseVer
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.list
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Recompiling a TSL exercise's grading script without authoring anything.
 *
 * The endpoint rewrites, in bulk, the code that decides students' grades. Three properties make
 * that safe rather than alarming, and all three are the kind that would be silently untrue:
 *
 * 1. **No version is created.** That is the whole point — a compiler fix is not an edit by the
 *    teacher, and a version chain full of entries nobody authored is a worse record than one with
 *    gaps. Asserted by counting rows in `exercise_version` and `automatic_exercise` either side.
 * 2. **The spec is never touched**, nor is anything else the teacher attached. Only the generated
 *    assets and the compile record change.
 * 3. **A spec that no longer compiles keeps the script it has.** The failure mode to avoid is an
 *    exercise left with no grading script at all, which would fail every submission rather than
 *    grade it wrongly.
 *
 * Plus the one that makes the dry run worth reading: a recompile whose output is identical writes
 * nothing, so "changed: 3" means three exercises and not "every exercise, because the timestamp
 * moved".
 */
@IntegrationTest
class AdminRecompileTslTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val controller: AdminRecompileTslController,
) {

    private val api = HttpApi(mockMvc)
    private val admin = Auth.ADMIN_ID
    private val teacher = Auth.TEACHER_ID

    /** A spec with one contains-test — enough to produce a real generated script. */
    private val spec = """
        {
          "language": "python3",
          "validateFiles": false,
          "requiredFiles": ["lahendus.py"],
          "tslVersion": "1.0.0",
          "tests": [
            {
              "type": "contains_test",
              "id": 1,
              "name": "Otsib silmust",
              "scope": "PROGRAM",
              "containsWhat": "KEYWORD_NO_ARG",
              "genericCheck": {
                "checkType": "ALL_OF_THESE",
                "expectedValue": ["for"],
                "beforeMessage": "kontrollin",
                "passedMessage": "leidsin",
                "failedMessage": "ei leidnud"
              }
            }
          ]
        }
    """.trimIndent()

    private var exerciseId = 0L
    private var autoExerciseId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin)
            Fixtures.teacher(teacher)
            exerciseId = Fixtures.autoExercise(
                "TSL exercise", teacher,
                gradingScript = "cd student-submission\npython generated_0.py",
                containerImageId = TSL_CONTAINER_IMAGE,
            )
            autoExerciseId = currentAutoExerciseId(exerciseId)

            Fixtures.asset(exerciseId, "tsl.json", spec)
            // A stale script, as an exercise compiled by a broken compiler would hold.
            Fixtures.asset(exerciseId, "generated_0.py", "# stale, compiled by an older compiler\n")
            Fixtures.asset(exerciseId, "meta.txt", "Compiled at: 2026-08-07 10:00:00")
        }
    }

    // --- the dry run -----------------------------------------------------------------------------

    @Test
    fun `a dry run reports what would change and writes nothing`() {
        val before = assetsOf(autoExerciseId)

        val resp = recompile(apply = false)
        assertEquals(200, resp.status) { resp.body }
        assertFalse(resp.jsonOrNull!!.get("applied").asBoolean())
        assertEquals(1, resp.jsonOrNull!!.get("changed").asInt())
        assertEquals(
            listOf("generated_0.py"),
            resp.elements("results").single().list("scripts").map { it.asString() },
        )

        assertEquals(before, assetsOf(autoExerciseId)) { "A dry run wrote to the database" }
    }

    @Test
    fun `applying rewrites the generated script and leaves the spec alone`() {
        assertEquals(200, recompile(apply = true).status)

        val after = assetsOf(autoExerciseId)
        assertEquals(spec, after["tsl.json"]) { "The spec was modified" }

        val generated = after["generated_0.py"]!!
        assertNotEquals("# stale, compiled by an older compiler\n", generated)
        assertTrue(generated.startsWith("from tiivad import *")) { generated.take(120) }
        assertTrue(generated.contains("Otsib silmust")) { "The test's name is not in the script" }
        // EZ-1774's defect, pinned at the far end of the whole pipeline rather than at the emitter.
        assertFalse(generated.contains("""\'check_type\'""")) { "Recompiled with the quoted-key defect" }
    }

    /**
     * The property the endpoint exists for.
     *
     * If this ever fails, recompiling has become an edit: the exercise gains a version that no
     * teacher authored, and the version chain stops being a record of authoring. It is also the
     * assertion most likely to be broken by someone "simplifying" this to reuse `updateExercise`.
     */
    @Test
    fun `no exercise version and no automatic exercise row are created`() {
        val versionsBefore = countRows(ExerciseVer)
        val autoBefore = countRows(AutoExercise)
        val validFromBefore = currentValidFrom(exerciseId)

        assertEquals(200, recompile(apply = true).status)

        assertEquals(versionsBefore, countRows(ExerciseVer)) { "A new exercise version was created" }
        assertEquals(autoBefore, countRows(AutoExercise)) { "A new automatic_exercise row was created" }
        assertEquals(autoExerciseId, currentAutoExerciseId(exerciseId)) { "The version was re-pointed" }
        assertEquals(validFromBefore, currentValidFrom(exerciseId)) { "The version's valid_from moved" }
    }

    @Test
    fun `a teacher's own assets survive`() {
        transaction { Fixtures.asset(exerciseId, "helper.py", "def helper(): pass") }

        assertEquals(200, recompile(apply = true).status)

        assertEquals("def helper(): pass", assetsOf(autoExerciseId)["helper.py"]) {
            "An asset the teacher attached was removed"
        }
    }

    // --- idempotence -----------------------------------------------------------------------------

    /**
     * A second run must report nothing and write nothing.
     *
     * `meta.txt` carries a compile timestamp, so a naive implementation rewrites every exercise on
     * every run and reports all of them as changed — which makes the dry run, the only safety
     * mechanism here, useless for spotting the exercises that actually matter.
     */
    @Test
    fun `recompiling twice changes nothing the second time`() {
        assertEquals(1, recompile(apply = true).jsonOrNull!!.get("changed").asInt())
        val afterFirst = assetsOf(autoExerciseId)

        val second = recompile(apply = true)
        assertEquals(0, second.jsonOrNull!!.get("changed").asInt())
        assertEquals(1, second.jsonOrNull!!.get("unchanged").asInt())
        assertEquals(afterFirst, assetsOf(autoExerciseId)) { "The second run rewrote the assets" }
    }

    // --- failure ---------------------------------------------------------------------------------

    /**
     * A spec that no longer compiles keeps the script it has.
     *
     * The tempting implementation deletes the generated assets and then writes the new ones. On a
     * spec the compiler rejects that leaves the exercise with no script at all — which fails every
     * submission, for everyone, immediately. Grading with a stale script is bad; grading with none
     * is worse and is the state a bulk operation could reach for hundreds of exercises at once.
     */
    @Test
    fun `an exercise whose spec no longer compiles is reported and left untouched`() {
        transaction {
            Asset.update({ (Asset.autoExercise eq autoExerciseId) and (Asset.fileName eq "tsl.json") }) {
                it[fileContent] = """{"tests": [{"type": "no_such_test_type", "id": 1}]}"""
            }
        }
        val before = assetsOf(autoExerciseId)

        val resp = recompile(apply = true)
        assertEquals(200, resp.status)
        assertEquals(1, resp.jsonOrNull!!.get("failed").asInt())
        assertEquals(0, resp.jsonOrNull!!.get("changed").asInt())

        val result = resp.elements("results").single()
        assertEquals("FAILED", result.get("outcome").asString())
        assertTrue(result.get("detail").asString().isNotBlank()) { "No reason given for the failure" }

        assertEquals(before, assetsOf(autoExerciseId)) { "A failed recompile modified the exercise" }
    }

    @Test
    fun `a TSL container with no spec is skipped rather than failed`() {
        transaction {
            Asset.deleteWhere { (Asset.autoExercise eq autoExerciseId) and (Asset.fileName eq "tsl.json") }
        }

        val result = recompile(apply = true).elements("results").single()
        assertEquals("SKIPPED", result.get("outcome").asString())
    }

    /**
     * A duplicated generated asset is collapsed to one.
     *
     * `asset` has no unique constraint on `(auto_exercise_id, file_name)`, and the migration runbook
     * records reaching that state for real — a spec written back with the `generated_0.py` a GET
     * returned stores it twice, once stale. Which copy the executor writes to disk is unspecified,
     * so such an exercise may already be grading with the wrong one. Recompiling repairs it.
     */
    @Test
    fun `a duplicated generated asset is collapsed to one`() {
        transaction { Fixtures.asset(exerciseId, "generated_0.py", "# a second, staler copy\n") }
        assertEquals(2, countAssetsNamed(autoExerciseId, "generated_0.py"))

        assertEquals(200, recompile(apply = true).status)

        assertEquals(1, countAssetsNamed(autoExerciseId, "generated_0.py"))
        assertTrue(assetsOf(autoExerciseId)["generated_0.py"]!!.startsWith("from tiivad import *"))
    }

    /**
     * And a duplicate is repaired **even when one of the copies is already correct**.
     *
     * This is the case the test above cannot see. With both copies stale, any implementation
     * repairs it; the interesting shape is one correct row and one stale one, where "did the content
     * change?" is decided by whichever row the map happened to keep. On the wrong side of that coin
     * flip the exercise is reported UNCHANGED and both rows survive — so change has to be detected
     * on the row *count* as well as the content.
     */
    @Test
    fun `a duplicate is repaired even when one copy already matches`() {
        // Learn what correct output looks like, then rebuild the pair with the stale copy *first*
        // and the correct one second. The order matters: with no ORDER BY, the row that survives
        // `toMap()` is the last one returned, so this is the arrangement in which content comparison
        // alone says "nothing to do" — the coin flip that leaves a duplicate in place. Inserting
        // them the other way round lands on the side any implementation repairs, which is why the
        // first version of this test passed against the unfixed code.
        assertEquals(200, recompile(apply = true).status)
        val correct = assetsOf(autoExerciseId)["generated_0.py"]!!

        transaction {
            Asset.deleteWhere { (Asset.autoExercise eq autoExerciseId) and (Asset.fileName eq "generated_0.py") }
            Fixtures.asset(exerciseId, "generated_0.py", "# a stale duplicate\n")
            Fixtures.asset(exerciseId, "generated_0.py", correct)
        }
        assertEquals(2, countAssetsNamed(autoExerciseId, "generated_0.py"))

        val resp = recompile(apply = true)
        assertEquals(1, resp.jsonOrNull!!.get("changed").asInt()) { "The duplicate was reported as unchanged" }
        assertEquals(1, countAssetsNamed(autoExerciseId, "generated_0.py")) { "The duplicate row survived" }
        assertEquals(correct, assetsOf(autoExerciseId)["generated_0.py"])
    }

    /**
     * An exercise saved mid-run is skipped, not written to the row it used to point at.
     *
     * The target list is resolved once and a real run takes minutes, so a teacher pressing save in
     * that window makes `UpdateExercise` insert a **new** `automatic_exercise` and re-point the
     * current version at it. Writing to the old row would be precisely the history rewrite this
     * endpoint promises never to do — and silently: the version that actually grades would keep its
     * stale script while the report said CHANGED.
     *
     * Driven through `recompileWithin` rather than the endpoint, because a single request scans and
     * writes with nothing in between and so cannot produce the interleaving. The docblock on that
     * method explains why it is `internal`.
     */
    @Test
    fun `a target whose version has moved on is skipped rather than written to the old row`() {
        val supersededScript = assetsOf(autoExerciseId)["generated_0.py"]!!

        // A second auto exercise, as UpdateExercise would create, with the version moved onto it.
        val newAutoExerciseId = transaction {
            val fresh = AutoExercise.insertAndGetId {
                it[gradingScript] = "cd student-submission\npython generated_0.py"
                it[containerImage] = EntityID(TSL_CONTAINER_IMAGE, ContainerImage)
                it[maxTime] = 6
                it[maxMem] = 300
            }.value
            ExerciseVer.update({ ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }) {
                it[autoExerciseId] = EntityID(fresh, AutoExercise)
            }
            fresh
        }

        // The target a scan taken *before* that save would be holding.
        val stale = AdminRecompileTslController.Target(exerciseId, autoExerciseId, "python generated_0.py")
        val result = transaction { controller.recompileWithin(stale, apply = true) }

        assertEquals(AdminRecompileTslController.Outcome.SKIPPED, result.outcome)
        assertTrue(result.detail!!.contains("saved while this run was in progress")) { result.detail!! }
        assertEquals(supersededScript, assetsOf(autoExerciseId)["generated_0.py"]) {
            "The superseded automatic_exercise was rewritten"
        }
        assertTrue(assetsOf(newAutoExerciseId).isEmpty()) { "The new row was written to as a side effect" }
    }

    /**
     * A generated script the grading command still names is kept, not swept as stale.
     *
     * The compiler emits `generated_0.py` today. An exercise holding a `generated_1.py` that its
     * grading script invokes would otherwise have it deleted as "not in the fresh output" — leaving
     * the exercise unable to grade at all, which is the outcome the FAILED path is careful to avoid,
     * reached through the one destructive step in the file.
     */
    @Test
    fun `a stale script the grading command still names is kept and reported`() {
        transaction {
            Fixtures.asset(exerciseId, "generated_1.py", "# a second script, still invoked\n")
            AutoExercise.update({ AutoExercise.id eq autoExerciseId }) {
                it[gradingScript] = "cd student-submission\npython generated_0.py && python generated_1.py"
            }
        }

        val resp = recompile(apply = true)
        assertEquals(200, resp.status)

        assertEquals("# a second script, still invoked\n", assetsOf(autoExerciseId)["generated_1.py"]) {
            "A script the grading command runs was deleted as stale"
        }
        val detail = resp.elements("results").single().get("detail").asString()
        assertTrue(detail.contains("generated_1.py")) { "The kept script was not reported: $detail" }
    }


    // --- scope -----------------------------------------------------------------------------------

    @Test
    fun `an exercise on another container is not touched`() {
        val other = transaction {
            val id = Fixtures.autoExercise("Not TSL", teacher, containerImageId = "silmused")
            Fixtures.asset(id, "tsl.json", spec)
            id
        }

        val resp = recompile(apply = true)
        assertEquals(1, resp.jsonOrNull!!.get("scanned").asInt())
        assertEquals(
            listOf(exerciseId),
            resp.elements("results").map { it.get("exercise_id").asLong() },
        ) { "A non-TSL exercise was scanned (other=$other)" }
    }

    /**
     * Only the current version.
     *
     * An older version's stored script is the record of what that version generated. Rewriting it
     * would be editing history to say something that was never true — and it can never run again,
     * because grading always reads the version with `valid_to IS NULL`.
     */
    @Test
    fun `a superseded version keeps its old script`() {
        // Retire the current version and give the exercise a fresh one with its own auto exercise.
        val supersededScript = "# what the old version generated\n"
        transaction {
            Asset.update({ (Asset.autoExercise eq autoExerciseId) and (Asset.fileName eq "generated_0.py") }) {
                it[fileContent] = supersededScript
            }
            ExerciseVer.update({ ExerciseVer.exercise eq exerciseId and ExerciseVer.validTo.isNull() }) {
                it[validTo] = TestClock.next()
            }
        }
        val newExerciseId = transaction {
            val id = Fixtures.autoExercise("Current", teacher, containerImageId = TSL_CONTAINER_IMAGE)
            Fixtures.asset(id, "tsl.json", spec)
            Fixtures.asset(id, "generated_0.py", "# stale\n")
            id
        }

        val resp = recompile(apply = true)
        assertEquals(
            listOf(newExerciseId),
            resp.elements("results").map { it.get("exercise_id").asLong() },
        )
        assertEquals(supersededScript, assetsOf(autoExerciseId)["generated_0.py"]) {
            "A superseded version's script was rewritten"
        }
    }

    /**
     * An id that is not a current TSL exercise is named, not silently dropped.
     *
     * Otherwise asking about a retired or renumbered exercise returns `scanned: 0`, which reads
     * exactly like "checked it, nothing to do" — and for a tool whose safety story is *read the dry
     * run first*, "I asked about two and it looked at one" has to be visible rather than inferred.
     */
    @Test
    fun `ids that match no current TSL exercise are reported`() {
        val resp = api.post(
            "/v2/admin/exercises/tsl/recompile",
            api.body(mapOf("apply" to false, "exercise_ids" to listOf(exerciseId, 999999L))),
            Auth.asAdmin(admin),
        )
        assertEquals(200, resp.status) { resp.body }
        assertEquals(1, resp.jsonOrNull!!.get("scanned").asInt())
        assertEquals(listOf(999999L), resp.jsonOrNull!!.list("not_found").map { it.asLong() })
    }

    @Test
    fun `only an admin may recompile`() {
        assertEquals(403, recompile(apply = true, caller = Auth.asTeacher(teacher)).status)
        assertEquals(403, recompile(apply = true, caller = Auth.asStudent("s")).status)
        assertEquals(401, api.post("/v2/admin/exercises/tsl/recompile", api.body("apply" to true), api.anonymous()).status)

        assertEquals("# stale, compiled by an older compiler\n", assetsOf(autoExerciseId)["generated_0.py"]) {
            "A refused request recompiled anyway"
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun recompile(apply: Boolean, caller: org.springframework.test.web.servlet.request.RequestPostProcessor = Auth.asAdmin(admin)) =
        api.post("/v2/admin/exercises/tsl/recompile", api.body("apply" to apply), caller)

    private fun assetsOf(autoExId: Long): Map<String, String> = transaction {
        Asset.select(Asset.fileName, Asset.fileContent)
            .where { Asset.autoExercise eq autoExId }
            .associate { it[Asset.fileName] to it[Asset.fileContent] }
    }

    private fun currentAutoExerciseId(exId: Long): Long = transaction {
        ExerciseVer.select(ExerciseVer.autoExerciseId)
            .where { ExerciseVer.exercise eq exId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.autoExerciseId]!!.value }
            .single()
    }

    private fun currentValidFrom(exId: Long) = transaction {
        ExerciseVer.select(ExerciseVer.validFrom)
            .where { ExerciseVer.exercise eq exId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.validFrom] }
            .single()
    }

    private fun countRows(table: org.jetbrains.exposed.v1.core.Table): Long =
        transaction { table.selectAll().count() }

    private fun countAssetsNamed(autoExId: Long, name: String): Long = transaction {
        Asset.selectAll().where { (Asset.autoExercise eq autoExId) and (Asset.fileName eq name) }.count()
    }
}
