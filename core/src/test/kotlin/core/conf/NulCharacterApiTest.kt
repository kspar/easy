package core.conf

import core.db.Exercise
import core.db.Submission
import core.db.TeacherInlineComment
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * A NUL character (U+0000) in a request body, refused as a 400 that names the field — see
 * [RejectNulCharacters].
 *
 * Before, PostgreSQL refused it at the insert, and the client got a 500 while ops got a system
 * notification mail. Each test here is one endpoint that stores free text, asserting the code, the
 * field and that nothing was stored; together they are the "every endpoint, not just submit" claim
 * the fix makes. The last one is the guard against the fix being too eager: the escape sequence
 * spelled out as text is ordinary source code and has to be accepted.
 *
 * `HttpApi.body` serialises a Kotlin `"\u0000"` as the JSON escape `\u0000`, which is how a real
 * client sends it — a raw 0x00 byte inside a JSON string is already a parse error.
 */
@IntegrationTest
class NulCharacterApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID

    private var courseId = 0L
    private var courseExId = 0L
    private var submissionId = 0L

    private val withNul = "print('a')\u0000\n"

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            courseId = Fixtures.course("NUL")
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, student)
            val exerciseId = Fixtures.exercise("Teacher-graded", teacher)
            courseExId = Fixtures.courseExercise(courseId, exerciseId)
            submissionId = Fixtures.submission(courseExId, student, number = 1)
        }
    }

    private fun HttpApi.Response.assertRefusedAt(field: String) {
        assertEquals(400, status) { body }
        assertEquals("TEXT_CONTAINS_NUL", errorCode) { body }
        assertEquals(field, jsonOrNull?.get("attrs")?.get("field")?.asString()) { body }
    }

    private fun submissions() = transaction { Submission.selectAll().count() }

    @Test
    fun `a student's solution with a NUL is refused, and not stored`() {
        api.post(
            "/v2/student/courses/$courseId/exercises/$courseExId/submissions",
            api.body("solution" to withNul),
            Auth.asStudent(student),
        ).assertRefusedAt("solution")

        assertEquals(1, submissions())
    }

    @Test
    fun `a solution submitted on a student's behalf with a NUL is refused, and not stored`() {
        api.post(
            "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions",
            api.body("solution" to withNul, "student_id" to student),
            Auth.asTeacher(teacher),
        ).assertRefusedAt("solution")

        assertEquals(1, submissions())
    }

    @Test
    fun `exercise text with a NUL is refused, and the field is named even inside a list`() {
        val exercise = mapOf(
            "title" to "New",
            "public" to false,
            "anonymous_autoassess_enabled" to false,
            "solution_file_name" to "solution.py",
            "solution_file_type" to "TEXT_EDITOR",
        )

        api.post(
            "/v2/exercises",
            api.body(exercise + mapOf("grader_type" to "TEACHER", "text_md" to withNul)),
            Auth.asTeacher(teacher),
        ).assertRefusedAt("text_md")

        api.post(
            "/v2/exercises",
            api.body(
                exercise + mapOf(
                    "grader_type" to "AUTO",
                    "grading_script" to "print('grading')",
                    "container_image" to "test-image",
                    "max_time_sec" to 6,
                    "max_mem_mb" to 300,
                    "assets" to listOf(
                        mapOf("file_name" to "ok.py", "file_content" to "fine"),
                        mapOf("file_name" to "bad.py", "file_content" to withNul),
                    ),
                )
            ),
            Auth.asTeacher(teacher),
        ).assertRefusedAt("assets[1].file_content")

        // The fixture's exercise only.
        assertEquals(1, transaction { Exercise.selectAll().count() })
    }

    @Test
    fun `feedback with a NUL is refused`() {
        api.post(
            "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/feedback",
            api.body("feedback_md" to withNul, "notify_student" to false),
            Auth.asTeacher(teacher),
        ).assertRefusedAt("feedback_md")
    }

    @Test
    fun `an inline comment with a NUL is refused, and not stored`() {
        api.post(
            "/v2/teacher/courses/$courseId/exercises/$courseExId/submissions/$submissionId/inline-comments",
            api.body("line_start" to 1, "line_end" to 1, "code" to "print('a')", "text_md" to withNul),
            Auth.asTeacher(teacher),
        ).assertRefusedAt("text_md")

        assertEquals(0, transaction { TeacherInlineComment.selectAll().count() })
    }

    @Test
    fun `the escape sequence written out as text is not a NUL, and is accepted`() {
        val submitted = api.post(
            "/v2/student/courses/$courseId/exercises/$courseExId/submissions",
            api.body("solution" to "print('\\u0000')\n"),
            Auth.asStudent(student),
        )

        assertEquals(200, submitted.status) { submitted.body }
        assertEquals(2, submissions())
    }
}
