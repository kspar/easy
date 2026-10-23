package core.aas

import core.db.AutoExercise
import core.db.Exercise
import core.exception.InvalidRequestException
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * An AUTO exercise created without its grading parameters, by `POST /v2/exercises`.
 *
 * This was a 400 with `code: null` and the message "Parameters for autoassessable exercise are
 * missing." — which parameters, it did not say — and, because the exception kept the default
 * `notify`, a system notification mail to ops for what is only a client leaving out a field. Found
 * stress-testing the dev app with a hand-written request; the web form always sends all five, so
 * only an API caller ever meets this, and that caller is exactly the one who needs to be told which.
 *
 * The mail itself is not observable here (system mail is off in the test config, and a mock of the
 * sender would fork the context), so the last test asserts the flag that decides it instead.
 */
@IntegrationTest
class InsertAutoExerciseTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID

    @BeforeEach
    fun populate() {
        transaction { Fixtures.teacher(teacher) }
    }

    private fun create(vararg auto: Pair<String, Any?>) = api.post(
        "/v2/exercises",
        api.body(
            mapOf(
                "title" to "Autograded",
                "public" to false,
                "anonymous_autoassess_enabled" to false,
                "grader_type" to "AUTO",
                "solution_file_name" to "solution.py",
                "solution_file_type" to "TEXT_EDITOR",
            ) + auto
        ),
        Auth.asTeacher(teacher),
    )

    private fun HttpApi.Response.missing() = jsonOrNull?.get("attrs")?.get("missing")?.asString()

    @Test
    fun `an AUTO exercise with none of its parameters is refused, naming all five`() {
        val refused = create()

        assertEquals(400, refused.status)
        assertEquals("INVALID_PARAMETER_VALUE", refused.errorCode)
        assertEquals("grading_script,container_image,max_time_sec,max_mem_mb,assets", refused.missing())

        // Refused before anything was written: the check is the first thing in the transaction.
        transaction {
            assertEquals(0, Exercise.selectAll().count())
            assertEquals(0, AutoExercise.selectAll().count())
        }
    }

    @Test
    fun `an AUTO exercise missing only its assets is refused, naming only the assets`() {
        val refused = create(
            "grading_script" to "print('grading')",
            "container_image" to "test-image",
            "max_time_sec" to 6,
            "max_mem_mb" to 300,
        )

        assertEquals(400, refused.status)
        assertEquals("INVALID_PARAMETER_VALUE", refused.errorCode)
        assertEquals("assets", refused.missing())
    }

    @Test
    fun `the refusal does not mail ops`() {
        val refused = assertThrows<InvalidRequestException> {
            insertAutoExercise("print('grading')", null, 6, 300, emptyList())
        }

        assertFalse(refused.notify)
        assertEquals("container_image", refused.attributes.toMap()["missing"])
    }
}
