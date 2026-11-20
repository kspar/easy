package core.ems.service.code

import core.conf.security.EasyRole
import core.db.ExerciseVer
import core.ems.service.code.similarity.SimilarityService
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.JsonNode

/**
 * `POST /v2/exercises/{exerciseId}/similarity`, end to end: which method compares an exercise, what a
 * teacher gets back, and what is refused.
 */
@IntegrationTest
class SimilarityApiTest(@Autowired mockMvc: MockMvc) {
    private val api = HttpApi(mockMvc)
    private val teacher = Auth.TEACHER_ID

    private var course = 0L
    private var exercise = 0L
    private var courseExercise = 0L

    private val original = """
        def count_words(text):
            words = text.split()
            result = {}
            for word in words:
                if word in result:
                    result[word] = result[word] + 1
                else:
                    result[word] = 1
            return result
    """.trimIndent()

    /** The same program with every name changed, laid out differently and commented: a disguised copy. */
    private val disguised = """
        # counts how often each item occurs
        def tally(s):

            items = s.split()
            acc = {}
            for item in items:
                if item in acc:
                    acc[item] = acc[item] + 1
                else:
                    acc[item] = 1

            return acc
    """.trimIndent()

    /** The same task, solved independently. */
    private val honest = """
        def count_words(sentence):
            counts = {}
            for w in sentence.lower().split():
                counts[w] = counts.get(w, 0) + 1
            return counts
    """.trimIndent()

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            course = Fixtures.course("Mine")
            Fixtures.enrolTeacher(course, teacher)
            exercise = Fixtures.exercise("Word count", teacher)
            courseExercise = Fixtures.courseExercise(course, exercise)
        }
    }

    private fun submit(student: String, solution: String, number: Int = 1): Long = transaction {
        if (number == 1) {
            Fixtures.student(student, givenName = student, familyName = "Student")
            Fixtures.enrolStudent(course, student)
        }
        Fixtures.submission(courseExercise, student, number = number, solution = solution)
    }

    private fun solutionFile(name: String) = transaction {
        ExerciseVer.update({ ExerciseVer.exercise eq exercise }) { it[solutionFileName] = name }
    }

    private fun check(ids: List<Long>, caller: String = teacher) = api.post(
        "/v2/exercises/$exercise/similarity",
        api.body(
            "courses" to listOf(mapOf("id" to course.toString())),
            "submissions" to ids.map { mapOf("id" to it.toString()) },
        ),
        Auth.asTeacher(caller),
    )

    private fun JsonNode.pairs(): Map<Set<String>, Int> =
        get("scores").toList().associate { setOf(it.get("sub_1").asString(), it.get("sub_2").asString()) to it.get("max_similarity").asInt() }

    @Test
    fun `a Python copy with every name changed is found by JPlag, where edit distance would score it low`() {
        val a = submit("ann", original)
        val b = submit("ben", disguised)
        val c = submit("cid", honest)

        val byJplag = check(listOf(a, b, c)).jsonOrNull!!
        assertEquals("jplag", byJplag.get("provider").asString())
        assertEquals("python", byJplag.get("language").asString())
        val copy = setOf("$a", "$b")
        val first = byJplag.get("scores").first()
        assertEquals(copy, setOf(first.get("sub_1").asString(), first.get("sub_2").asString()), "the copy is not the top pair: $byJplag")
        assertTrue(byJplag.pairs().getValue(copy) >= 90, "$byJplag")
        // And where it matched, as lines on both sides, for the page to mark.
        assertTrue(first.get("matches").toList().isNotEmpty())

        // The same three as a text exercise: edit distance, which renaming does change.
        solutionFile("answer.txt")
        val byEditDistance = check(listOf(a, b, c)).jsonOrNull!!
        assertEquals("edit-distance", byEditDistance.get("provider").asString())
        assertEquals("text", byEditDistance.get("language").asString())
        assertTrue(byEditDistance.pairs().getValue(copy) < byJplag.pairs().getValue(copy) - 20, "$byEditDistance")
    }

    @Test
    fun `prose handed in to a Python exercise is compared as text, so unrelated essays do not score as copies`() {
        val essay = "Kassid on loomad, kes magavad palju.\nNad armastavad sooja kohta päikese käes.\nMõni kass püüab hiiri."
        val other = "Programmeerimine on oskus, mida õpitakse harjutades.\nIga päev natuke koodi kirjutades areneb mõtlemine."
        val a = submit("ann", essay)
        // The same essay with a word in capitals, a zero-width space inside it, and an ä written as a and a combining mark.
        val b = submit("ben", essay.replace("loomad", "LOO\u200BMAD").replace("päikese", "pa\u0308ikese"))
        val c = submit("cid", other)

        val json = check(listOf(a, b, c)).jsonOrNull!!
        assertEquals("jplag", json.get("provider").asString())
        assertEquals("text", json.get("language").asString())
        assertEquals(100, json.pairs().getValue(setOf("$a", "$b")))
        assertTrue((json.pairs()[setOf("$a", "$c")] ?: 0) < 30, "$json")
    }

    @Test
    fun `a student's own attempts are never paired with each other`() {
        val first = submit("ann", original)
        val second = submit("ann", original.replace("result", "res"), number = 2)
        val other = submit("ben", disguised)

        val json = check(listOf(first, second, other)).jsonOrNull!!
        assertEquals(setOf(setOf("$first", "$other"), setOf("$second", "$other")), json.pairs().keys)
    }

    @Test
    fun `a submission that cannot be compared is named with its reason, not silently left out`() {
        val blank = submit("ann", "   \n")
        val nested = submit("ben", "x = " + "(".repeat(250) + "1" + ")".repeat(250))
        // Over the 10,000-token cap; and one statement written out 2,000 times, whose every pair would take minutes.
        val large = submit("dan", "x = 1\n".repeat(3_400))
        val repetitive = submit("eve", "x = 1\n".repeat(2_000))
        val ordinary = submit("cid", original)

        val json = check(listOf(blank, nested, large, repetitive, ordinary)).jsonOrNull!!
        assertEquals(5, json.get("compared").asInt())
        assertEquals(
            mapOf("$blank" to "TOO_SMALL", "$nested" to "TOO_COMPLEX", "$large" to "TOO_LARGE", "$repetitive" to "TOO_REPETITIVE"),
            json.get("skipped").toList().associate { it.get("id").asString() to it.get("reason").asString() },
        )
        assertTrue(json.get("scores").toList().isEmpty())
    }

    @Test
    fun `more submissions than one check takes are refused, naming the limit, whether listed or not`() {
        listOf("ann", "ben", "cid", "dan").forEach { submit(it, original) }
        val controller = TeacherCheckSimilarityController(SimilarityService(timeoutSec = 60), maxSubmissions = 3)
        fun refusal(ids: List<Long>?) = assertThrows<InvalidRequestException> {
            val request = TeacherCheckSimilarityController.Req(
                listOf(TeacherCheckSimilarityController.ReqCourse(course.toString())),
                ids?.map { TeacherCheckSimilarityController.ReqSubmission(it.toString()) },
            )
            controller.controller(exercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER))
        }
        // All four on the course, none listed.
        val unlisted = refusal(null)
        // Four listed that do not exist: refused before they are looked up, so not as "not found".
        val listed = refusal((1L..4L).map { 900_000 + it })
        for (refused in listOf(unlisted, listed)) {
            assertEquals(ReqError.TOO_MANY_SUBMISSIONS, refused.code)
            assertEquals(listOf("limit" to "3", "requested" to "4"), refused.attributes.toList())
        }
    }

    @Test
    fun `a course listed over and over is checked as one course`() {
        val ids = listOf(submit("ann", original), submit("ben", disguised))
        val controller = TeacherCheckSimilarityController(SimilarityService(timeoutSec = 60), maxSubmissions = 3)
        // More copies than a query takes parameters: checked copy by copy, the request would fail.
        val request = TeacherCheckSimilarityController.Req(
            List(70_000) { TeacherCheckSimilarityController.ReqCourse(course.toString()) },
            ids.map { TeacherCheckSimilarityController.ReqSubmission(it.toString()) },
        )
        val resp = controller.controller(exercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER))
        assertEquals(2, resp.compared)
    }

    @Test
    fun `a teacher not on the course cannot check its submissions`() {
        val ids = listOf(submit("ann", original), submit("ben", disguised))
        transaction { Fixtures.teacher("stranger") }
        assertEquals(403, check(ids, caller = "stranger").status)
    }
}
