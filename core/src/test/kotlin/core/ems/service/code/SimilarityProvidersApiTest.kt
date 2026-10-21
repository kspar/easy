package core.ems.service.code

import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * Choosing a similarity provider, end to end: what an admin sets through the admin endpoints is what the
 * check then runs with, and with nothing set it runs as before - edit distance.
 */
@IntegrationTest
class SimilarityProvidersApiTest(@Autowired mockMvc: MockMvc) {
    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val admin = Auth.ADMIN_ID

    private var course = 0L
    private var exercise = 0L
    private var submissions = listOf<Long>()

    private val solution = """
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

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.admin(admin)
            course = Fixtures.course("Mine")
            Fixtures.enrolTeacher(course, teacher)
            exercise = Fixtures.exercise("Word count", teacher)
            val courseExercise = Fixtures.courseExercise(course, exercise)
            submissions = (1..2).map { n ->
                val student = "student-$n"
                Fixtures.student(student)
                Fixtures.enrolStudent(course, student)
                Fixtures.submission(courseExercise, student, number = 1, solution = solution)
            }
        }
    }

    private fun check() = api.post(
        "/v2/exercises/$exercise/similarity",
        api.body(
            "courses" to listOf(mapOf("id" to course.toString())),
            "submissions" to submissions.map { mapOf("id" to it.toString()) },
        ),
        Auth.asTeacher(teacher),
    )

    private fun putProvider(id: String, enabled: Boolean, priority: Int, languages: List<Pair<String, Boolean>> = emptyList()) =
        api.put(
            "/v2/admin/similarity-providers/$id",
            api.body("enabled" to enabled, "priority" to priority, "languages" to languages.map { mapOf("language" to it.first, "enabled" to it.second) }),
            Auth.asAdmin(admin),
        )

    private fun putLanguage(language: String, enabled: Boolean) =
        api.put("/v2/admin/similarity-languages/$language", api.body("enabled" to enabled), Auth.asAdmin(admin))

    @Test
    fun `with nothing set the check runs on edit distance, as similarity always has`() {
        val response = check()
        assertEquals(200, response.status)
        assertEquals("edit-distance", response.field("provider"))
        assertEquals(100, response.elements("scores").single().get("max_similarity").asInt())
    }

    @Test
    fun `an admin's choice is what the next check runs on`() {
        assertEquals(200, putProvider("jplag", enabled = true, priority = 200).status)
        assertEquals("jplag", check().field("provider"))

        assertEquals(200, putProvider("jplag", enabled = false, priority = 200).status)
        assertEquals("edit-distance", check().field("provider"))
    }

    @Test
    fun `the providers are listed in the order a language is offered to them, with what is default and what is set`() {
        putProvider("jplag", enabled = true, priority = 200, languages = listOf("sql" to true))
        val response = api.get("/v2/admin/similarity-providers", Auth.asAdmin(admin))
        assertEquals(200, response.status)

        val providers = response.elements("providers")
        assertEquals(listOf("jplag", "edit-distance"), providers.map { it.get("id").asString() })
        val jplag = providers.first()
        assertTrue(jplag.get("overridden").asBoolean())
        assertEquals(50, jplag.get("default_priority").asInt())
        val sql = jplag.get("languages").toList().single { it.get("language").asString() == "sql" }
        assertEquals("experimental", sql.get("support").asString())
        assertTrue(sql.get("enabled").asBoolean())

        val python = response.elements("languages").single { it.get("language").asString() == "python" }
        assertEquals("jplag", python.get("provider").asString())
    }

    @Test
    fun `a language switched off is refused by name, and listed as compared by nobody`() {
        assertEquals(200, putLanguage("python", enabled = false).status)
        val response = check()
        assertEquals(400, response.status)
        assertEquals("SIMILARITY_UNAVAILABLE", response.field("code"))

        val python = api.get("/v2/admin/similarity-providers", Auth.asAdmin(admin)).elements("languages")
            .single { it.get("language").asString() == "python" }
        assertTrue(python.get("provider").isNull)

        assertEquals(200, putLanguage("python", enabled = true).status)
        assertEquals(200, check().status)
    }

    private fun putSettings(provider: String?, mode: String = "optimised", courseId: Long = course) =
        api.put("/v2/courses/$courseId/similarity-settings", api.body("provider" to provider, "mode" to mode), Auth.asTeacher(teacher))

    @Test
    fun `a course with nothing set has no provider pinned, and the providers it may pin`() {
        val response = api.get("/v2/courses/$course/similarity-settings", Auth.asTeacher(teacher))
        assertEquals(200, response.status)
        assertTrue(response.jsonOrNull!!.get("provider").isNull)
        assertEquals("optimised", response.field("mode"))
        assertEquals(listOf("edit-distance", "jplag"), response.elements("providers").map { it.get("id").asString() })
    }

    @Test
    fun `a course's pinned provider compares its checks, against the admin's order`() {
        putProvider("jplag", enabled = true, priority = 200)
        assertEquals(200, putSettings("edit-distance", mode = "full").status)
        val response = check()
        assertEquals("edit-distance", response.field("provider"))
        assertEquals("full", response.field("mode"))
        // Edit distance says how alike, not where: the page is told to find the shared code itself.
        assertEquals(false, response.jsonOrNull!!.get("match_ranges").asBoolean())
        assertTrue(response.jsonOrNull!!.get("provider_fallback").isNull)
        val score = response.elements("scores").single()
        assertEquals(listOf("levenshtein", "dice"), score.get("metrics").toList().map { it.get("id").asString() })
        // The pre-JPlag page labels score_a "Shared text" and score_b "Edit distance": for edit distance
        // they are Dice and Levenshtein, as they were, not the mean and the maximum.
        val metric = score.get("metrics").toList().associate { it.get("id").asString() to it.get("value").asInt() }
        assertEquals(metric.getValue("dice"), score.get("score_a").asInt())
        assertEquals(metric.getValue("levenshtein"), score.get("score_b").asInt())

        assertEquals(200, putSettings(null).status)
        val byOrder = check()
        assertEquals("jplag", byOrder.field("provider"))
        assertEquals(true, byOrder.jsonOrNull!!.get("match_ranges").asBoolean())
        val metrics = byOrder.elements("scores").single().get("metrics").toList().associate { it.get("id").asString() to it.get("unit").asString() }
        assertEquals(mapOf("similarity" to "percent", "max_similarity" to "percent", "shared_tokens" to "count"), metrics)
        // With no Dice to give, the old names carry the two similarities, as they did under JPlag.
        val jplagScore = byOrder.elements("scores").single()
        assertEquals(jplagScore.get("similarity").asInt(), jplagScore.get("score_a").asInt())
        assertEquals(jplagScore.get("max_similarity").asInt(), jplagScore.get("score_b").asInt())
    }

    @Test
    fun `a pin the admin has since switched off is passed over, and the check says so`() {
        assertEquals(200, putSettings("jplag").status)
        putProvider("jplag", enabled = false, priority = 50)
        val response = check()
        assertEquals("edit-distance", response.field("provider"))
        assertEquals("PINNED_UNAVAILABLE", response.field("provider_fallback"))
        // And it cannot be pinned again while off.
        assertEquals(400, putSettings("jplag").status)
    }

    @Test
    fun `the old page's score_a and score_b are edit distance's Dice and Levenshtein, as it labels them`() {
        // The pre-JPlag page labels score_a "Shared text" and score_b "Edit distance". Two different
        // solutions, so that Dice, Levenshtein, their mean and their maximum are four different numbers.
        val ids = transaction {
            val other = Fixtures.exercise("Two programs", teacher)
            val courseExercise = Fixtures.courseExercise(course, other)
            val first = Fixtures.submission(courseExercise, "student-1", number = 1, solution = solution)
            val second = Fixtures.submission(courseExercise, "student-2", number = 1, solution = solution.replace("result", "counts").replace("    else:\n        result[word] = 1\n", ""))
            other to listOf(first, second)
        }
        val response = api.post(
            "/v2/exercises/${ids.first}/similarity",
            api.body("courses" to listOf(mapOf("id" to course.toString())), "submissions" to ids.second.map { mapOf("id" to it.toString()) }),
            Auth.asTeacher(teacher),
        )
        assertEquals("edit-distance", response.field("provider"))
        val score = response.elements("scores").single()
        val metric = score.get("metrics").toList().associate { it.get("id").asString() to it.get("value").asInt() }
        assertTrue(metric.getValue("dice") != metric.getValue("levenshtein"), "the two measures agree here, so the test cannot tell them apart: $metric")
        assertEquals(metric.getValue("dice"), score.get("score_a").asInt())
        assertEquals(metric.getValue("levenshtein"), score.get("score_b").asInt())
    }

    @Test
    fun `a language switched off as a whole is not listed as one a provider compares`() {
        // Listed, the dialog offered a provider for a language every check then refused.
        assertEquals(200, putLanguage("python", enabled = false).status)
        val providers = api.get("/v2/courses/$course/similarity-settings", Auth.asTeacher(teacher)).elements("providers")
        for (provider in providers) {
            val languages = provider.get("languages").toList().map { it.asString() }
            assertTrue("python" !in languages, "${provider.get("id").asString()} still lists python: $languages")
            assertTrue("java" in languages)
        }
    }

    @Test
    fun `only the course's own teachers may read or change its settings`() {
        val other = transaction { Fixtures.course("Not mine") }
        assertEquals(403, api.get("/v2/courses/$other/similarity-settings", Auth.asTeacher(teacher)).status)
        assertEquals(403, putSettings(null, courseId = other).status)
        assertEquals(400, putSettings(null, mode = "everything").status)
    }

    @Test
    fun `providers are code, so an unknown one or a language it does not compare is refused`() {
        assertEquals(400, putProvider("moss", enabled = true, priority = 10).status)
        assertEquals(400, putProvider("jplag", enabled = true, priority = 10, languages = listOf("cobol" to true)).status)
        assertEquals(400, putLanguage("cobol", enabled = false).status)
    }
}
