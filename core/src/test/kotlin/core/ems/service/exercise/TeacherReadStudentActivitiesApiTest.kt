package core.ems.service.exercise

import core.db.Account
import core.db.CourseExercise
import core.db.Submission
import core.db.TeacherActivity
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * `GET /v2/teacher/courses/{courseId}/exercises/{courseExerciseId}/students/{studentId}/activities`,
 * and specifically that the course in the path constrains the exercise in the path.
 *
 * The endpoint takes three ids and used to check one of them. `teacherOnCourse(courseId)` answered
 * "does the caller teach this course?", and then the query it reached filtered on the course exercise
 * and the student and nothing else — so the answer to a question about a course the caller *does*
 * teach authorised a read on a course exercise belonging to one they do not. Both ids arrive from the
 * path independently and nothing else related them.
 *
 * **Why the existing suite could not see this.** `EndpointAuthorizationMatrixTest` drives every
 * endpoint as every role, which varies the caller and holds the path fixed;
 * `EndpointSecuritySurfaceTest` checks that a `@Secured` annotation is present. Both were green
 * throughout, and would be green again if the check below were removed, because neither asks whether
 * a handler constrains the object it returns to the object the caller was authorised for. That is a
 * per-endpoint question, so it needs a per-endpoint test — this one.
 *
 * The shape is two courses that share nothing: [teacherA] teaches [courseA] and only that, the
 * student's work is all on [courseB]. Anything [teacherA] can see here they should not be able to.
 */
@IntegrationTest
class TeacherReadStudentActivitiesApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val teacherA = Auth.TEACHER_ID
    private val teacherB = "other-teacher"
    private val studentId = Auth.STUDENT_ID

    /** Distinctive on purpose: the negative assertions look for it anywhere in the response body. */
    private val feedback = "Your loop never terminates when n is negative."

    private var courseA = 0L
    private var courseB = 0L
    private var courseExOnB = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacherA)
            Fixtures.teacher(teacherB)
            Fixtures.student(studentId)

            courseA = Fixtures.course("A course teacher A teaches")
            Fixtures.enrolTeacher(courseA, teacherA)

            courseB = Fixtures.course("A course teacher A has nothing to do with")
            Fixtures.enrolTeacher(courseB, teacherB)
            Fixtures.enrolStudent(courseB, studentId)

            val exercise = Fixtures.exercise("Sum of two numbers", teacherB)
            courseExOnB = Fixtures.courseExercise(courseB, exercise)
            val newSubmissionId = Fixtures.submission(courseExOnB, studentId, number = 1)

            TeacherActivity.insert {
                it[courseExercise] = EntityID(courseExOnB, CourseExercise)
                it[TeacherActivity.student] = EntityID(studentId, Account)
                it[TeacherActivity.submission] = EntityID(newSubmissionId, Submission)
                it[teacher] = EntityID(teacherB, Account)
                it[mergeWindowStart] = TestClock.next()
                it[grade] = 42
                it[feedbackMd] = feedback
                it[feedbackHtml] = "<p>$feedback</p>"
            }
        }
    }

    private fun read(courseId: Long, courseExId: Long, caller: String) = api.get(
        "/v2/teacher/courses/$courseId/exercises/$courseExId/students/$studentId/activities",
        Auth.asTeacher(caller),
    )

    @Test
    fun `the teacher of the course reads the activity`() {
        // The control. Without it every assertion below could be passing because the fixture never
        // produced a readable activity in the first place.
        val resp = read(courseB, courseExOnB, teacherB)
        assertEquals(200, resp.status) { resp.body }
        assertEquals(1, resp.elements("teacher_activities").size) { resp.body }
        assertEquals(feedback, resp.elements("teacher_activities").single().get("feedback_md").asText()) { resp.body }
    }

    @Test
    fun `a teacher cannot pass their own course id to read an exercise on someone else's`() {
        // The defect. `courseA` satisfies `teacherOnCourse`; `courseExOnB` is what is actually read.
        val resp = read(courseA, courseExOnB, teacherA)
        assertNotEquals(200, resp.status) { "read across courses succeeded: ${resp.body}" }
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", resp.errorCode) { resp.body }
        assertFalse(resp.body.contains(feedback)) { "the feedback leaked in an error body: ${resp.body}" }
    }

    @Test
    fun `naming the right course does not help a teacher who does not teach it`() {
        // The first guard, still doing its job. Kept because the fix adds a second check and it
        // would be easy for a later change to end up relying on the new one alone.
        val resp = read(courseB, courseExOnB, teacherA)
        assertNotEquals(200, resp.status) { "read on an unrelated course succeeded: ${resp.body}" }
        assertFalse(resp.body.contains(feedback)) { "the feedback leaked in an error body: ${resp.body}" }
    }

    @Test
    fun `an exercise id from no course at all is not found`() {
        val resp = read(courseA, courseExOnB + 10_000, teacherA)
        assertNotEquals(200, resp.status) { resp.body }
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", resp.errorCode) { resp.body }
    }
}
