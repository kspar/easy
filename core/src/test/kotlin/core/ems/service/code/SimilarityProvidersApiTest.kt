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

    @Test
    fun `providers are code, so an unknown one or a language it does not compare is refused`() {
        assertEquals(400, putProvider("moss", enabled = true, priority = 10).status)
        assertEquals(400, putProvider("jplag", enabled = true, priority = 10, languages = listOf("cobol" to true)).status)
        assertEquals(400, putLanguage("cobol", enabled = false).status)
    }
}
