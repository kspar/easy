package core.ems.service.ai

import core.db.AiFeedback
import core.db.AiFeedbackStatus
import core.db.AiProviderType
import core.db.AutoGradeStatus
import core.db.Course
import core.db.CourseExercise
import core.testing.Auth
import core.testing.FakeAnthropic
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * `POST /v2/student/courses/{c}/exercises/{ce}/submissions/{s}/ai-feedback`, end to end: the
 * guards, the wire call to a [FakeAnthropic], the audit row, and the explanation showing up in
 * both activity feeds.
 *
 * Every negative case here is one the button's own gating in the web client also enforces, which
 * is exactly why they need a server-side test: the client is the easy half to get around.
 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StudentAiFeedbackApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)
    private val anthropic = FakeAnthropic()

    private val student = Auth.STUDENT_ID
    private val otherStudent = "other-student"
    private val teacher = Auth.TEACHER_ID

    /** Distinctive, so an assertion can find the grader's output inside the prompt core sent. */
    private val okV3 = """{"result_type":"OK_V3","producer":"tiivad","pre_evaluate_error":null,"points":40,""" +
            """"tests":[{"title":"Handles negatives","status":"FAIL","user_inputs":["-1","2"],""" +
            """"actual_output":"3","exception_message":null,"checks":[{"status":"FAIL","feedback":"Expected 1"}]}]}"""

    private val solution = "print(abs(int(input())) + int(input()))"

    private var courseId = 0L
    private var courseExId = 0L
    private var submissionId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        anthropic.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            Fixtures.student(otherStudent)
            courseId = Fixtures.course(
                "Programming", aiProvider = AiProviderType.ANTHROPIC, aiApiKey = "sk-ant-course-key",
                aiBaseUrl = anthropic.baseUrl, aiModel = "claude-test-model",
            )
            Fixtures.enrolTeacher(courseId, teacher)
            Fixtures.enrolStudent(courseId, student)
            Fixtures.enrolStudent(courseId, otherStudent)
            val exercise = Fixtures.autoExercise("Sum of two numbers", teacher)
            courseExId = Fixtures.courseExercise(courseId, exercise, aiExplanationsPerStudent = 3)
            submissionId = failedSubmission(student, number = 1)
        }
    }

    @AfterAll
    fun stop() = anthropic.close()

    /** A submission the autograder has scored below 100, with its OK_V3 feedback. */
    private fun failedSubmission(studentId: String, number: Int, grade: Int = 40): Long {
        val id = Fixtures.submission(
            courseExId, studentId, number = number, grade = grade, solution = solution,
            isAutoGrade = true, autoGradeStatus = AutoGradeStatus.COMPLETED,
        )
        Fixtures.autogradeActivity(courseExId, studentId, id, grade, okV3)
        return id
    }

    private fun explain(id: Long = submissionId, caller: String = student, language: String = "et") = api.post(
        "/v2/student/courses/$courseId/exercises/$courseExId/submissions/$id/ai-feedback",
        api.body("language" to language),
        Auth.asStudent(caller),
    )

    private fun studentFeed() = api.get("/v2/student/courses/$courseId/exercises/$courseExId/activities", Auth.asStudent(student))

    private fun teacherFeed() = api.get(
        "/v2/teacher/courses/$courseId/exercises/$courseExId/students/$student/activities", Auth.asTeacher(teacher)
    )

    private fun rows() = transaction { AiFeedback.selectAll().map { it } }

    @Test
    fun `explains a failed latest submission and stores the audit row`() {
        anthropic.respond(FakeAnthropic.Behaviour.Answer("Sinu programm võtab *absoluutväärtuse*.", inputTokens = 800, outputTokens = 20))

        val resp = explain()

        assertEquals(200, resp.status) { resp.body }
        assertEquals("Sinu programm võtab *absoluutväärtuse*.", resp.field("feedback_md"))
        assertTrue(resp.field("feedback_html")!!.contains("<em>absoluutväärtuse</em>")) { "Markdown was not rendered: ${resp.body}" }
        assertEquals("ANTHROPIC", resp.field("provider"))
        assertEquals("1", resp.field("submission_number"))
        assertEquals(submissionId.toString(), resp.field("submission_id"))

        // What went over the wire: the course's key, the course's model, and a prompt carrying the
        // grader's output and the student's code verbatim.
        val sent = anthropic.requests.single()
        assertEquals("sk-ant-course-key", sent.apiKey)
        assertTrue(sent.body.contains("\"model\":\"claude-test-model\"")) { sent.body }
        assertTrue(sent.body.contains("Handles negatives")) { "Grader output missing from the prompt" }
        assertTrue(sent.body.contains("Sum of two numbers")) { "Exercise title missing from the prompt" }
        assertTrue(sent.body.contains("abs(int(input()))")) { "Solution missing from the prompt" }
        assertTrue(sent.body.contains("Reply in Estonian.")) { "Language instruction missing" }

        val row = rows().single()
        assertEquals(AiFeedbackStatus.OK, row[AiFeedback.status])
        assertEquals(800, row[AiFeedback.tokensIn])
        assertEquals(20, row[AiFeedback.tokensOut])
        assertTrue(row[AiFeedback.prompt].contains("Handles negatives"))
        assertTrue(row[AiFeedback.responseRaw]!!.contains("msg_test"))
        assertEquals("claude-test", row[AiFeedback.model]) { "The model column is what the provider said it ran" }
    }

    @Test
    fun `the explanation appears in the student's feed and the teacher's, with no teacher on it`() {
        assertEquals(200, explain().status)

        val mine = studentFeed()
        assertEquals(200, mine.status) { mine.body }
        val entry = mine.elements("ai_feedback").single()
        assertEquals(submissionId.toString(), entry.get("submission_id").asString())
        assertEquals("The loop stops one step early.", entry.get("feedback_md").asString())
        assertEquals(null, entry.get("teacher")) { "An AI entry must not carry a teacher" }
        assertEquals(null, entry.get("prompt")) { "The audit columns stay off the wire" }
        assertEquals(0, mine.elements("teacher_activities").size)

        val theirs = teacherFeed()
        assertEquals(200, theirs.status) { theirs.body }
        assertEquals(1, theirs.elements("ai_feedback").size)
    }

    @Test
    fun `a second request returns the same row and costs nothing`() {
        val first = explain()
        val second = explain(language = "en")

        assertEquals(200, second.status) { second.body }
        assertEquals(first.field("id"), second.field("id"))
        assertEquals(1, anthropic.requests.size) { "The provider was called again" }
        assertEquals(1, rows().size)
    }

    @Test
    fun `English when asked`() {
        assertEquals(200, explain(language = "en").status)
        assertTrue(anthropic.requests.single().body.contains("Reply in English."))
    }

    @Test
    fun `someone else's submission does not exist`() {
        val resp = explain(caller = otherStudent)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", resp.errorCode) { resp.body }
        assertTrue(anthropic.requests.isEmpty())
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a course without a key has no AI`() {
        transaction {
            Course.update({ Course.id eq courseId }) { it[aiApiKey] = null }
        }
        val resp = explain()
        assertEquals("AI_NOT_CONFIGURED", resp.errorCode) { resp.body }
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `a submission that passed has nothing to explain`() {
        val passed = transaction { failedSubmission(student, number = 2, grade = 100) }
        val resp = explain(passed)
        assertEquals("AI_FEEDBACK_NOT_AVAILABLE", resp.errorCode) { resp.body }
        assertTrue(anthropic.requests.isEmpty())
    }

    @Test
    fun `only the latest submission can be explained`() {
        transaction { failedSubmission(student, number = 2) }
        val resp = explain(submissionId) // number 1, no longer the latest
        assertEquals("AI_FEEDBACK_NOT_AVAILABLE", resp.errorCode) { resp.body }
    }

    @Test
    fun `a submission still being graded is not ready`() {
        val pending = transaction {
            Fixtures.submission(courseExId, student, number = 2, autoGradeStatus = AutoGradeStatus.IN_PROGRESS)
        }
        val resp = explain(pending)
        assertEquals("AI_FEEDBACK_NOT_AVAILABLE", resp.errorCode) { resp.body }
    }

    @Test
    fun `a provider failure is reported, kept for the audit, and does not block a retry`() {
        anthropic.respond(FakeAnthropic.Behaviour.Fail(500))

        val failed = explain()
        assertEquals("AI_PROVIDER_ERROR", failed.errorCode) { failed.body }

        val row = rows().single()
        assertEquals(AiFeedbackStatus.FAILED, row[AiFeedback.status])
        assertTrue(row[AiFeedback.responseRaw]!!.contains("boom"))
        assertEquals(null, row[AiFeedback.feedbackMd])

        // Nothing failed reaches the feed.
        assertEquals(0, studentFeed().elements("ai_feedback").size)

        // And the FAILED row is not the one-per-submission row: the provider recovers, the retry wins.
        anthropic.respond(FakeAnthropic.Behaviour.Answer("Now it works."))
        val retried = explain()
        assertEquals(200, retried.status) { retried.body }
        assertEquals(2, rows().size)
        assertEquals(1, studentFeed().elements("ai_feedback").size)
    }

    @Test
    fun `a refusal is a provider failure too, and is charged like an answer`() {
        anthropic.respond(FakeAnthropic.Behaviour.Refusal)
        val resp = explain()
        assertEquals("AI_PROVIDER_ERROR", resp.errorCode) { resp.body }
        val row = rows().single()
        assertEquals(AiFeedbackStatus.FAILED, row[AiFeedback.status])
        assertEquals(10, row[AiFeedback.tokensIn])
        assertEquals(10L, tokensUsed()) { "The vendor billed the refusal; so must the counter" }
    }

    @Test
    fun `legacy plain-text feedback is sent the same way`() {
        val legacy = transaction {
            val id = Fixtures.submission(
                courseExId, student, number = 2, grade = 40, solution = solution,
                isAutoGrade = true, autoGradeStatus = AutoGradeStatus.COMPLETED,
            )
            Fixtures.autogradeActivity(courseExId, student, id, 40, "Test 1: FAIL\nExpected 1, got 3\n")
            id
        }
        assertEquals(200, explain(legacy).status)
        assertTrue(anthropic.requests.single().body.contains("Expected 1, got 3"))
    }

    private fun tokensUsed(): Long = transaction {
        Course.select(Course.aiTokensUsed).where { Course.id eq courseId }.single()[Course.aiTokensUsed]
    }

    private fun setBudget(budget: Long?, used: Long) = transaction {
        Course.update({ Course.id eq courseId }) {
            it[aiTokenBudget] = budget
            it[aiTokensUsed] = used
        }
    }

    @Test
    fun `every answer is charged to the course's counter, in plus out`() {
        anthropic.respond(FakeAnthropic.Behaviour.Answer("One.", inputTokens = 700, outputTokens = 30))
        assertEquals(200, explain().status)
        assertEquals(730L, tokensUsed())

        // A second submission, a second charge; the idempotent re-read of the first costs nothing.
        assertEquals(200, explain().status)
        assertEquals(730L, tokensUsed())
        val second = transaction { failedSubmission(student, number = 2) }
        assertEquals(200, explain(second).status)
        assertEquals(1460L, tokensUsed())
    }

    @Test
    fun `an HTTP failure, which the vendor does not bill, is not charged`() {
        anthropic.respond(FakeAnthropic.Behaviour.Fail(500))
        explain()
        assertEquals(0L, tokensUsed())
    }

    @Test
    fun `a spent budget refuses before the provider is called, and hides the button`() {
        setBudget(budget = 1000, used = 1000)

        val resp = explain()
        assertEquals("AI_LIMIT_REACHED", resp.errorCode) { resp.body }
        assertEquals("token_budget", resp.jsonOrNull?.get("attrs")?.get("limit")?.asString())
        assertTrue(anthropic.requests.isEmpty()) { "The provider was called with the budget spent" }
        assertTrue(rows().isEmpty())

        val page = api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student))
        assertEquals("false", page.field("ai_feedback_enabled")) { page.body }
    }

    @Test
    fun `the last request under budget goes through and may overshoot`() {
        setBudget(budget = 1000, used = 999)
        anthropic.respond(FakeAnthropic.Behaviour.Answer("One.", inputTokens = 700, outputTokens = 30))
        assertEquals(200, explain().status)
        assertEquals(1729L, tokensUsed())
        // ...and the next one does not.
        val second = transaction { failedSubmission(student, number = 2) }
        assertEquals("AI_LIMIT_REACHED", explain(second).errorCode)
    }

    private fun setPerStudent(n: Int) = transaction {
        CourseExercise.update({ CourseExercise.id eq courseExId }) { it[aiExplanationsPerStudent] = n }
    }

    private fun explanationsLeft(): String? =
        api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student)).field("ai_explanations_left")

    @Test
    fun `zero per student means the exercise has AI off`() {
        setPerStudent(0)
        val resp = explain()
        assertEquals("AI_FEEDBACK_NOT_AVAILABLE", resp.errorCode) { resp.body }
        assertTrue(anthropic.requests.isEmpty())

        val page = api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student))
        assertEquals("false", page.field("ai_feedback_enabled"))
        assertEquals("0", page.field("ai_explanations_left"))
    }

    @Test
    fun `the per-student allowance counts down and then refuses`() {
        setPerStudent(2)
        assertEquals("2", explanationsLeft())

        assertEquals(200, explain().status)
        assertEquals("1", explanationsLeft())
        // The same submission again is the idempotent re-read, not a second explanation.
        assertEquals(200, explain().status)
        assertEquals("1", explanationsLeft())

        val second = transaction { failedSubmission(student, number = 2) }
        assertEquals(200, explain(second).status)
        assertEquals("0", explanationsLeft())
        val page = api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student))
        assertEquals("false", page.field("ai_feedback_enabled")) { "Allowance spent, button must go" }

        val third = transaction { failedSubmission(student, number = 3) }
        val refused = explain(third)
        assertEquals("AI_LIMIT_REACHED", refused.errorCode) { refused.body }
        assertEquals("per_student", refused.jsonOrNull?.get("attrs")?.get("limit")?.asString())
        assertEquals("2", refused.jsonOrNull?.get("attrs")?.get("allowed")?.asString())
        assertEquals(2, anthropic.requests.size) { "The provider was called for the refused one" }

        // Another student's count is their own.
        val theirs = transaction { failedSubmission(otherStudent, number = 1) }
        assertEquals(200, explain(theirs, caller = otherStudent).status)
    }

    @Test
    fun `a failed attempt does not use up the allowance`() {
        setPerStudent(1)
        anthropic.respond(FakeAnthropic.Behaviour.Fail(500))
        explain()
        assertEquals("1", explanationsLeft())
        anthropic.respond(FakeAnthropic.Behaviour.Answer("Now."))
        assertEquals(200, explain().status)
        assertEquals("0", explanationsLeft())
    }

    @Test
    fun `a teacher sets the allowance through the exercise settings`() {
        val resp = api.patch(
            "/v2/courses/$courseId/exercises/$courseExId",
            api.body("replace" to mapOf("ai_explanations_per_student" to 7)),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, resp.status) { resp.body }
        assertEquals("7", explanationsLeft())

        val details = api.get("/v2/teacher/courses/$courseId/exercises/$courseExId", Auth.asTeacher(teacher))
        assertEquals("7", details.field("ai_explanations_per_student")) { details.body }

        val negative = api.patch(
            "/v2/courses/$courseId/exercises/$courseExId",
            api.body("replace" to mapOf("ai_explanations_per_student" to -1)),
            Auth.asTeacher(teacher),
        )
        assertEquals(400, negative.status) { negative.body }
    }

    @Test
    fun `a solution longer than the course allows is refused before the provider is called`() {
        transaction { Course.update({ Course.id eq courseId }) { it[aiMaxSolutionChars] = 40 } }

        // 39 characters: under.
        val short = transaction {
            val id = Fixtures.submission(
                courseExId, student, number = 2, grade = 40, solution = "x = 1\n".repeat(6) + "pri",
                isAutoGrade = true, autoGradeStatus = AutoGradeStatus.COMPLETED,
            )
            Fixtures.autogradeActivity(courseExId, student, id, 40, okV3)
            id
        }
        assertEquals(200, explain(short).status)

        val long = transaction {
            val id = Fixtures.submission(
                courseExId, student, number = 3, grade = 40, solution = "x = 1\n".repeat(7),
                isAutoGrade = true, autoGradeStatus = AutoGradeStatus.COMPLETED,
            )
            Fixtures.autogradeActivity(courseExId, student, id, 40, okV3)
            id
        }
        val refused = explain(long)
        assertEquals("AI_LIMIT_REACHED", refused.errorCode) { refused.body }
        assertEquals("solution_length", refused.jsonOrNull?.get("attrs")?.get("limit")?.asString())
        assertEquals("40", refused.jsonOrNull?.get("attrs")?.get("allowed")?.asString())
        assertEquals(1, anthropic.requests.size)

        val page = api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student))
        assertEquals("40", page.field("ai_max_solution_chars")) { page.body }
    }

    @Test
    fun `no budget means no limit`() {
        setBudget(budget = null, used = 5_000_000)
        assertEquals(200, explain().status)
    }

    @Test
    fun `the student exercise page is told whether AI is on`() {
        val on = api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student))
        assertEquals("true", on.field("ai_feedback_enabled")) { on.body }

        transaction {
            Course.update({ Course.id eq courseId }) { it[aiProvider] = null }
        }
        val off = api.get("/v2/student/courses/$courseId/exercises/$courseExId", Auth.asStudent(student))
        assertEquals("false", off.field("ai_feedback_enabled")) { off.body }
        assertFalse(off.body.contains("sk-ant")) { "The key leaked into the exercise page" }
        assertNotNull(off.field("effective_title"))
    }
}
