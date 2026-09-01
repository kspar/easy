package core.ems.service.moodle

import core.db.Account
import core.db.Course
import core.db.CourseExercise
import core.db.CourseExerciseExceptionGroup
import core.db.CourseGroup
import core.db.StudentCourseAccess
import core.db.StudentCourseGroup
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.testing.Auth
import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestTemplate

/**
 * What a Moodle student sync does to the *groups* on the course it syncs.
 *
 * Group management belongs to Moodle on a synced course: the sync creates groups it has not seen
 * before, rebuilds every membership from the response, and — since EZ-1615 gave the protocol a
 * top-level `groups` list — deletes the groups Moodle no longer has. None of that had a test, and
 * it is the half of the sync that *destroys* rows rather than replacing them: a deleted group takes
 * its per-group exercise exceptions with it through an `ON DELETE CASCADE`, and no later sync puts
 * those deadlines back. The delete is also reported at `log.debug`, so in production it is silent.
 *
 * The three cases below are one distinction: what the response *says* about groups.
 *
 * - a populated list means "these are the groups" — create, keep, delete the rest;
 * - an empty list means "this course has no groups" — delete all of them, which is correct and is
 *   asserted here so that the guard in the third case cannot be widened into covering it;
 * - **an absent list says nothing about groups at all**, and used to be read as an empty one via
 *   `orEmpty()`. That is the case worth a guard: a plugin that stops sending the field, and the
 *   empty-body fallback that used to manufacture `MoodleResponse(emptyList())`, would each have
 *   deleted every group on every synced course, with the exceptions cascading, on the strength of a
 *   response that never mentioned them. The empty body now throws instead, and an absent list
 *   leaves the groups alone.
 *
 * The Moodle call is stubbed at the `RestTemplate` rather than mocked at the service: what is under
 * test is the SQL the response turns into, so the JSON in these tests is the protocol's own shape,
 * deserialized by the same Jackson mapping production uses. Binding to the service's own template
 * keeps this on the shared Spring context — a `@MockitoBean` would fork a second one.
 */
@IntegrationTest
class MoodleGroupSyncTest(@Autowired private val syncService: MoodleStudentsSyncService) {

    private lateinit var restTemplate: RestTemplate
    private lateinit var originalRequestFactory: ClientHttpRequestFactory
    private lateinit var moodle: MockRestServiceServer

    // Not `student` and `teacher`: an Exposed insert block takes the *table* as its receiver, so a
    // property by a column's name is shadowed by that column inside it.
    private val teacherId = Auth.TEACHER_ID
    private val studentId = Auth.STUDENT_ID

    private var courseId = 0L
    private var labId = 0L
    private var oldLabId = 0L
    private var courseExerciseId = 0L

    @BeforeEach
    fun populate() {
        TestClock.reset()

        restTemplate = ReflectionTestUtils.getField(syncService, "restTemplate") as RestTemplate
        originalRequestFactory = restTemplate.requestFactory
        moodle = MockRestServiceServer.bindTo(restTemplate).build()

        transaction {
            Fixtures.teacher(teacherId)
            Fixtures.student(studentId)
            courseId = Fixtures.course(
                "Programming", moodleShortName = "MOODLE-PROG", moodleSyncStudents = true,
            )
            // "Lab 1" is the group Moodle still has, "Old lab" the one it does not.
            labId = Fixtures.courseGroup(courseId, "Lab 1")
            oldLabId = Fixtures.courseGroup(courseId, "Old lab")

            // A student already enrolled through Moodle. The moodle_username is what the sync matches
            // on to tell an existing access from a new pending one.
            StudentCourseAccess.insert {
                it[StudentCourseAccess.student] = EntityID(studentId, Account)
                it[course] = EntityID(courseId, Course)
                it[moodleUsername] = "moodle-mati"
                it[createdAt] = TestClock.next()
            }

            val exerciseId = Fixtures.exercise("Loops", teacherId)
            courseExerciseId = Fixtures.courseExercise(courseId, exerciseId)
            exceptionFor(labId)
            exceptionFor(oldLabId)
        }
    }

    @AfterEach
    fun unbind() {
        // The service is a singleton on the context every other test shares, so the mock factory has
        // to come off again — left in place it would answer, or refuse, their Moodle calls too.
        restTemplate.requestFactory = originalRequestFactory
    }

    /** A per-group deadline exception, the row that cascades when a group is deleted. */
    private fun exceptionFor(groupId: Long) {
        CourseExerciseExceptionGroup.insert {
            it[courseExercise] = EntityID(courseExerciseId, CourseExercise)
            it[courseGroup] = EntityID(groupId, CourseGroup)
            it[isExceptionSoftDeadline] = false
            it[isExceptionHardDeadline] = true
            it[hardDeadline] = TestClock.farFuture()
            it[isExceptionStudentVisibleFrom] = false
        }
    }

    private fun respondWith(json: String) {
        moodle.expect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(json))
    }

    private fun groupNames(): List<String> = transaction {
        CourseGroup.select(CourseGroup.name)
            .where { CourseGroup.course eq courseId }
            .map { it[CourseGroup.name] }
            .sorted()
    }

    private fun groupIdsByName(): Map<String, Long> = transaction {
        CourseGroup.selectAll()
            .where { CourseGroup.course eq courseId }
            .associate { it[CourseGroup.name] to it[CourseGroup.id].value }
    }

    private fun exceptionGroupIds(): List<Long> = transaction {
        CourseExerciseExceptionGroup.select(CourseExerciseExceptionGroup.courseGroup)
            .map { it[CourseExerciseExceptionGroup.courseGroup].value }
            .sorted()
    }

    private fun membershipGroupIds(): List<Long> = transaction {
        StudentCourseGroup.select(StudentCourseGroup.courseGroup)
            .where { StudentCourseGroup.course eq courseId }
            .map { it[StudentCourseGroup.courseGroup].value }
            .sorted()
    }

    @Test
    fun `a group Moodle no longer has is deleted, a new one created, an existing one kept`() {
        respondWith(
            """
            {
              "students": [
                {
                  "username": "moodle-mati",
                  "firstname": "Mati",
                  "lastname": "Maasikas",
                  "email": "Mati@example.test",
                  "groups": ["7", "9"]
                }
              ],
              "groups": [
                {"id": "7", "name": "Lab 1"},
                {"id": "9", "name": "Lab 2"}
              ]
            }
            """.trimIndent()
        )

        syncService.syncStudents(courseId)
        moodle.verify()

        assertEquals(listOf("Lab 1", "Lab 2"), groupNames()) {
            "The sync did not end with exactly the groups Moodle sent."
        }

        // Kept, not deleted-and-recreated. The identity matters more than the name: everything
        // pointing at a group — exercise exceptions, and any teacher's saved selection — points at
        // its id, so a group that survives a sync under a new id has silently lost all of it.
        val afterIds = groupIdsByName()
        assertEquals(labId, afterIds.getValue("Lab 1")) { "The surviving group was recreated under a new id." }

        // The deleted group's exception went with it, the survivor's stayed. This is the cascade the
        // deletion is worth being careful about, asserted rather than assumed.
        assertEquals(listOf(labId), exceptionGroupIds()) {
            "Exercise exceptions did not follow the groups they belong to."
        }

        // Memberships are rebuilt from the response, so the student is in both groups Moodle listed.
        assertEquals(
            listOf(labId, afterIds.getValue("Lab 2")).sorted(),
            membershipGroupIds(),
        ) { "Group memberships were not rebuilt from the response." }
    }

    @Test
    fun `an empty groups list deletes every group on the course`() {
        // The response Moodle sends for a course whose groups have all been removed. Distinct from
        // the absent-field case below, and the reason that case needed a nullable check rather than
        // an emptiness check.
        respondWith(
            """
            {
              "students": [
                {
                  "username": "moodle-mati",
                  "firstname": "Mati",
                  "lastname": "Maasikas",
                  "email": "mati@example.test",
                  "groups": []
                }
              ],
              "groups": []
            }
            """.trimIndent()
        )

        syncService.syncStudents(courseId)
        moodle.verify()

        assertEquals(emptyList<String>(), groupNames()) { "Moodle said there are no groups and there still are." }
        assertEquals(emptyList<Long>(), exceptionGroupIds())
    }

    @Test
    fun `a response with no groups field at all leaves the groups alone`() {
        // EZ-1615's protocol always sends the field; this is the response of a Moodle end that has
        // stopped doing so. Read as "no groups", it would take both groups and both exception rows
        // with it — a silent, unrecoverable loss triggered by a *missing* piece of information.
        //
        // The guard covers the groups, not the memberships: those are rebuilt from each student's
        // own group list, so a response with no groups to resolve them against still empties them.
        // That is recoverable — the next well-formed sync puts every membership back — which is
        // exactly what deleting the groups is not.
        respondWith(
            """
            {
              "students": [
                {
                  "username": "moodle-mati",
                  "firstname": "Mati",
                  "lastname": "Maasikas",
                  "email": "mati@example.test"
                }
              ]
            }
            """.trimIndent()
        )

        syncService.syncStudents(courseId)
        moodle.verify()

        assertEquals(listOf("Lab 1", "Old lab"), groupNames()) {
            "A response that said nothing about groups deleted them."
        }
        assertEquals(listOf(labId, oldLabId).sorted(), exceptionGroupIds()) {
            "A response that said nothing about groups dropped per-group exercise exceptions."
        }
    }

    @Test
    fun `an empty response body fails the sync instead of emptying the course`() {
        moodle.expect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON))

        // Not a roster of nobody. The sync replaces the course's students with what it is given, so
        // reading a body Moodle failed to send as an empty list unenrolled everyone on the course.
        val e = assertThrows(InvalidRequestException::class.java) { syncService.syncStudents(courseId) }
        // The code the web client already has a translated message for, rather than the generic
        // linking error it shares with every other Moodle failure.
        assertEquals(ReqError.MOODLE_EMPTY_RESPONSE, e.code)

        assertEquals(listOf("Lab 1", "Old lab"), groupNames()) { "An empty response deleted the groups." }
        assertEquals(1L, transaction {
            StudentCourseAccess.selectAll().where { StudentCourseAccess.course eq courseId }.count()
        }) { "An empty response unenrolled the students." }
    }
}
