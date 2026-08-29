package core.ems.service.moodle

import core.ems.service.moodle.MoodleGradesSyncService.Companion.encodeGradeRequest
import core.ems.service.moodle.MoodleGradesSyncService.Companion.isGradeSyncSuccess
import core.ems.service.moodle.MoodleGradesSyncService.MoodleReq
import core.ems.service.moodle.MoodleGradesSyncService.MoodleReqExercise
import core.ems.service.moodle.MoodleGradesSyncService.MoodleReqGrade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The wire shape of the grade sync request, and how a successful response is recognised.
 *
 * **Both of these were wrong in production and nothing caught it**, which is the reason this file
 * exists rather than a general belief that encoding deserves a test. EZ-1688 (4c527f65) moved the
 * Moodle integration onto web services by adding `wstoken`/`wsfunction`/`moodlewsrestformat` to both
 * requests. The students call survived because its only payload field, `shortname`, is a scalar and
 * reads the same under either protocol. The grades call did not: its body stayed
 * `data=<the whole request as one JSON string>`, shaped for the `/local/lahendus/import.php` script
 * that the same commit stopped calling. And its success check stayed `body.contains("done")`, while
 * the new function answers `{"success": true}`.
 *
 * So the migration left two defects that a compiler cannot see, in a path that runs on every
 * submission — and they stayed invisible for seven weeks because the grades sync had never once been
 * pointed at a real Moodle. It surfaced on 2026-08-29 as an `invalid_parameter_exception` the first
 * time anybody tried.
 *
 * The shapes below are not guesses. All three candidate encodings were sent to the live
 * `local_ut_custom_update_manual_grades` that day: bracketed-at-top-level answered
 * `{"success": true}`, while `data=<json>` and `data[exercises][0][...]` were both rejected with
 * `invalidparameter`. What is pinned here is what Moodle actually accepted.
 *
 * These are unit tests over the two pure functions, so they say nothing about whether Moodle still
 * agrees — only that we have not drifted from what it accepted. The live check is
 * doc/dev-environment.md §5.1.
 */
class MoodleGradeRequestTest {

    private val req = MoodleReq(
        shortname = "PILOOT_1",
        exercises = listOf(
            MoodleReqExercise(
                idnumber = "6481",
                title = "Astendamine",
                grades = listOf(MoodleReqGrade("kaspar_p", 0))
            )
        )
    )

    // --- the request -------------------------------------------------------------------------

    @Test
    fun `encodes one exercise and one grade as bracketed top-level fields`() {
        val form = encodeGradeRequest(req)

        assertEquals(listOf("PILOOT_1"), form["shortname"])
        assertEquals(listOf("6481"), form["exercises[0][idnumber]"])
        assertEquals(listOf("Astendamine"), form["exercises[0][title]"])
        assertEquals(listOf("kaspar_p"), form["exercises[0][grades][0][username]"])
        assertEquals(listOf("0"), form["exercises[0][grades][0][grade]"])
    }

    @Test
    fun `sends no data field at all`() {
        // The specific regression. Both rejected spellings put the payload under `data`, so a `data`
        // key reappearing here means the old encoding has come back in some form.
        val form = encodeGradeRequest(req)
        assertFalse(form.keys.any { it == "data" || it.startsWith("data[") }, "form was: ${form.keys}")
    }

    @Test
    fun `indexes several exercises and several grades independently`() {
        val many = MoodleReq(
            shortname = "C",
            exercises = listOf(
                MoodleReqExercise("1", "First", listOf(MoodleReqGrade("a", 10), MoodleReqGrade("b", 20))),
                MoodleReqExercise("2", "Second", listOf(MoodleReqGrade("c", 30)))
            )
        )
        val form = encodeGradeRequest(many)

        assertEquals(listOf("1"), form["exercises[0][idnumber]"])
        assertEquals(listOf("a"), form["exercises[0][grades][0][username]"])
        assertEquals(listOf("20"), form["exercises[0][grades][1][grade]"])
        assertEquals(listOf("2"), form["exercises[1][idnumber]"])
        assertEquals(listOf("c"), form["exercises[1][grades][0][username]"])
        // The second exercise restarts its own grade indexing rather than continuing the first's.
        assertEquals(listOf("30"), form["exercises[1][grades][0][grade]"])
    }

    @Test
    fun `an exercise with no grades still carries its identity`() {
        // batchGrades emits these deliberately — "sync exercises with no grades" — because that call
        // is what creates the row in Moodle's gradebook. So the exercise has to survive encoding
        // rather than vanish.
        //
        // **Moodle rejects exactly this request**, and it is not fixable here: form encoding cannot
        // express an empty array — the key simply disappears, as asserted below — and the function
        // requires `grades` to be present. Nine spellings were tried against the live function on
        // 2026-08-29, including `grades=`, `grades[]=`, `grades=[]`, a JSON body, and batching the
        // ungraded exercise with a graded one; all refused. The old JSON-body protocol could say
        // `"grades": []`; this one cannot.
        //
        // So this shape is now only the fallback, for a course with no Moodle-linked students to
        // name. Where there is one, batchGrades sends a single entry with a null grade instead —
        // see the test above — which Moodle accepts and which creates the row.
        //
        // syncCourseGradesToMoodle tolerates the refusal either way rather than failing the sync.
        val empty = MoodleReq("C", listOf(MoodleReqExercise("9", "Untouched", emptyList())))
        val form = encodeGradeRequest(empty)

        assertEquals(listOf("9"), form["exercises[0][idnumber]"])
        assertEquals(listOf("Untouched"), form["exercises[0][title]"])
        assertTrue(form.keys.none { it.contains("[grades]") }, "form was: ${form.keys}")
    }

    @Test
    fun `a null grade names the student and omits the grade key`() {
        // How an exercise nobody has a grade for still gets its gradebook row created. `grades` must
        // be non-empty, but `grade` inside an entry is optional — so this entry satisfies the
        // parameter and asks for nothing to be written, and Moodle leaves the cell blank.
        //
        // The distinction is the entire trick and it is one character wide: `grade=` (empty) is
        // refused, an absent `grade` is accepted. A number would be accepted too and then clamped to
        // the item's minimum, putting a visible 0 on somebody who did not earn it — which is what
        // makes the obvious version of this workaround worse than useless.
        val form = encodeGradeRequest(
            MoodleReq("C", listOf(MoodleReqExercise("7", "Nobody has done this", listOf(
                MoodleReqGrade("some_student", null)
            ))))
        )

        assertEquals(listOf("7"), form["exercises[0][idnumber]"])
        assertEquals(listOf("some_student"), form["exercises[0][grades][0][username]"])
        assertFalse(
            form.keys.any { it == "exercises[0][grades][0][grade]" },
            "the grade key must be absent, not empty; form was: ${form.keys}"
        )
    }

    @Test
    fun `grades are sent as strings because a form has no other type`() {
        val form = encodeGradeRequest(MoodleReq("C", listOf(
            MoodleReqExercise("1", "T", listOf(MoodleReqGrade("u", 100)))
        )))
        assertEquals(listOf("100"), form["exercises[0][grades][0][grade]"])
    }

    // --- the response ------------------------------------------------------------------------

    @Test
    fun `success is the JSON Moodle actually returns`() {
        assertTrue(isGradeSyncSuccess("""{"success":true}"""))
        assertTrue(isGradeSyncSuccess("""{"success": true}"""))
    }

    @Test
    fun `a Moodle exception body is not success`() {
        // The exact body that surfaced the bug.
        val error = """{"exception":"core\\exception\\invalid_parameter_exception",""" +
                """"errorcode":"invalidparameter","message":"Tuvastati sobimatu parameetriväärtus"}"""
        assertFalse(isGradeSyncSuccess(error))
    }

    @Test
    fun `success false is not success`() {
        assertFalse(isGradeSyncSuccess("""{"success":false}"""))
    }

    @Test
    fun `the old done check is gone`() {
        // A body from the retired protocol must no longer count as success — otherwise a half-
        // migrated Moodle would look fine while the current function was never being called.
        assertFalse(isGradeSyncSuccess("done"))
        assertFalse(isGradeSyncSuccess("""{"status":"done"}"""))
    }

    @Test
    fun `the word success appearing in a failure is not success`() {
        // Why this is parsed rather than substring-matched.
        assertFalse(isGradeSyncSuccess("""{"errorcode":"nopermissions","message":"success required"}"""))
    }

    @Test
    fun `unparseable and empty bodies are not success`() {
        assertFalse(isGradeSyncSuccess(null))
        assertFalse(isGradeSyncSuccess(""))
        assertFalse(isGradeSyncSuccess("<html>Service temporarily unavailable</html>"))
    }
}
