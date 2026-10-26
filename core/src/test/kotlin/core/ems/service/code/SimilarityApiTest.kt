package core.ems.service.code

import core.conf.security.EasyRole
import core.db.CourseSimilaritySettings
import core.db.SimilarityMode
import core.db.SimilarityProviderLanguage
import core.db.SimilarityProviderSetting
import core.ems.service.code.similarity.ManualTicker
import core.ems.service.code.similarity.SimilarityService
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * `POST /v2/exercises/{exerciseId}/similarity` — who may call it, and what it refuses.
 *
 * The engine itself is covered without a Spring context in [SimilarityServiceTest]; this is about
 * the controller, and mostly about access control. The exercise's own metadata is read here to pick
 * a tokeniser, and an earlier version read it by `exerciseId` alone — so a teacher could confirm any
 * exercise's existence, and an empty `courses` list asserted nothing at all while still reaching
 * that read. Those two are the cases most worth pinning.
 */
@IntegrationTest
class SimilarityApiTest(@Autowired mockMvc: MockMvc) {
    private val api = HttpApi(mockMvc)

    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID
    private val admin = Auth.ADMIN_ID

    private var myCourse = 0L
    private var myExercise = 0L
    private var otherExercise = 0L
    private var myCourseExercise = 0L
    private var studentSubmission = 0L
    private var teacherSubmission = 0L

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

    /** The same program with every identifier renamed. */
    private val renamed = solution
        .replace("count_words", "tally").replace("text", "s")
        .replace("words", "items").replace("word", "item").replace("result", "acc")

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.teacher(teacher)
            Fixtures.student(student)
            Fixtures.admin(admin)

            myCourse = Fixtures.course("Mine")
            Fixtures.enrolTeacher(myCourse, teacher)
            Fixtures.enrolStudent(myCourse, student)

            myExercise = Fixtures.exercise("Word count", teacher)
            val courseExercise = Fixtures.courseExercise(myCourse, myExercise)
            myCourseExercise = courseExercise
            studentSubmission = Fixtures.submission(courseExercise, student, number = 1, solution = solution)
            teacherSubmission = Fixtures.submission(courseExercise, teacher, number = 1, solution = renamed)

            // On a course the caller does not teach, and never named in any request below.
            val otherCourse = Fixtures.course("Somebody else's")
            otherExercise = Fixtures.exercise("Private", admin)
            Fixtures.courseExercise(otherCourse, otherExercise)

            // These tests are about JPlag's answers, which an admin chooses over the default edit
            // distance - SQL included, which JPlag offers only as experimental. SimilarityProvidersApiTest
            // covers the choosing.
            SimilarityProviderSetting.insert {
                it[provider] = "jplag"
                it[enabled] = true
                it[priority] = 1000
            }
            SimilarityProviderLanguage.insert {
                it[provider] = "jplag"
                it[language] = "sql"
                it[enabled] = true
            }
        }
    }

    /** The course compares every pair, which a run over its budget is refused for. */
    private fun fullMode() = transaction {
        CourseSimilaritySettings.insert {
            it[course] = myCourse
            it[provider] = null
            it[mode] = SimilarityMode.FULL
        }
    }

    private fun run(exerciseId: Long, courses: List<Long>, caller: String = teacher) =
        api.post(
            "/v2/exercises/$exerciseId/similarity",
            api.body("courses" to courses.map { mapOf("id" to it.toString()) }),
            Auth.asTeacher(caller),
        )

    /** The page's request shape: explicit submission ids, which is all it ever sends. */
    private fun runWith(submissionIds: List<Long>, courses: List<Long> = listOf(myCourse)) =
        api.post(
            "/v2/exercises/$myExercise/similarity",
            api.body(
                "courses" to courses.map { mapOf("id" to it.toString()) },
                "submissions" to submissionIds.map { mapOf("id" to it.toString()) },
            ),
            Auth.asTeacher(teacher),
        )

    // --- 1. it works at all ---

    @Test
    fun `a teacher on the course gets token-based scores and the resolved language`() {
        val response = run(myExercise, listOf(myCourse))

        assertEquals(200, response.status)
        assertEquals("python", response.field("language"))
        assertEquals(1, response.elements("scores").size)
        // Renaming every identifier is the case the replaced text scorer could not see.
        val score = response.elements("scores").single()
        assertTrue(
            score.get("similarity").asInt() > 90,
            "a pure rename scored ${score.get("similarity").asInt()}",
        )
        assertTrue(response.elements("skipped").isEmpty())
    }

    @Test
    fun `an exercise graded by Silmused is compared as SQL although its solution file still says py`() {
        // Every new exercise starts as lahendus.py, and the executor writes every submission under that
        // name anyway - which is why Silmused's template renames the file itself - so an SQL exercise
        // often keeps it. Read by the file name alone, its SQL was compared as Python.
        val sqlExercise = transaction {
            val exercise = Fixtures.autoExercise("SQL lab", teacher, containerImageId = "silmused")
            val courseExercise = Fixtures.courseExercise(myCourse, exercise, orderIdx = 3)
            val query = "SELECT e.nimetus, COUNT(*) AS arv FROM eriala e JOIN tudeng t ON t.eriala_id = e.id GROUP BY e.nimetus ORDER BY arv DESC;"
            Fixtures.submission(courseExercise, student, number = 1, solution = query)
            Fixtures.submission(courseExercise, teacher, number = 1, solution = query.lowercase().replace(" e.", " m.").replace(" e ", " m "))
            exercise
        }

        val response = run(sqlExercise, listOf(myCourse))

        assertEquals(200, response.status)
        assertEquals("sql", response.field("language"))
        assertEquals(100, response.elements("scores").single().get("similarity").asInt())
    }

    // --- 2. access control ---

    @Test
    fun `a student may not run it at all`() {
        val response = api.post(
            "/v2/exercises/$myExercise/similarity",
            api.body("courses" to listOf(mapOf("id" to myCourse.toString()))),
            Auth.asStudent(student),
        )
        assertEquals(403, response.status)
    }

    @Test
    fun `an empty course list is refused rather than silently asserting nothing`() {
        // It registered zero access checks, which was harmless only until the exercise's own row was
        // read on the same path - at which point any teacher could probe any exercise id.
        val response = run(myExercise, emptyList())

        assertEquals(400, response.status)
        assertEquals("INVALID_PARAMETER_VALUE", response.errorCode)
    }

    @Test
    fun `an exercise on none of the named courses is not readable, existing or not`() {
        // The same answer either way is the point: a different status for a real exercise would make
        // this an existence oracle over the whole exercise table.
        val real = run(otherExercise, listOf(myCourse))
        val absent = run(999_999L, listOf(myCourse))

        assertEquals(400, real.status)
        assertEquals(400, absent.status)
        assertEquals(absent.errorCode, real.errorCode)
    }

    // --- 3. the cap ---

    @Test
    fun `a cohort above the cap is refused with a named code and the limit, not a 500`() {
        // The refusal is raised before any solution is read, outside the try/catch that maps it - so
        // it escaped as a bare RuntimeException and the endpoint answered 500 with an empty body
        // while mailing ops. The service-level test could not see that.
        //
        // Four *different* students, not four attempts by one. Only the latest submission per student
        // is compared now, so four attempts by one student are one row and never reach the cap - an
        // earlier version of this fixture did exactly that and stopped testing anything.
        //
        // Deleting the controller's check fails this, since the service no longer re-checks the count
        // and this cohort would be compared and answered 200. Moving it to after the load would not:
        // that order is pinned by `the cap is checked on the named ids, before any of them is looked up`.
        transaction {
            val courseExercise = Fixtures.courseExercise(myCourse, myExercise, orderIdx = 2)
            repeat(4) { i ->
                val id = Fixtures.student("capped-student-$i")
                Fixtures.submission(courseExercise, id, number = 1, solution = solution)
            }
        }

        val response = run(myExercise, listOf(myCourse))

        assertEquals(400, response.status)
        assertEquals("TOO_MANY_SUBMISSIONS", response.errorCode)
        // The attributes are what let the UI name the actual number instead of saying "too many".
        assertEquals("3", response.jsonOrNull?.get("attrs")?.get("limit")?.asString())
    }

    @Test
    fun `resubmitting does not let a student bury the evidence against themselves`() {
        // Called without explicit submission ids, this used to compare every attempt ever made, so a
        // student's own resubmissions paired with each other at ~100%. Enough of them fill the
        // 100-pair cap and push the genuine copy out of the response entirely - evidence suppression
        // that costs the student nothing, since resubmitting is normal and encouraged.
        //
        // Added to the student who already has one, so the cohort stays two students however many
        // attempts there are. Before the fix this was six rows and fifteen pairs; the test config's
        // cap of 3 means it would not even have got as far as comparing them.
        transaction {
            repeat(4) { Fixtures.submission(myCourseExercise, student, number = it + 2, solution = solution) }
        }

        val response = run(myExercise, listOf(myCourse))
        assertEquals(200, response.status)
        val body = response.jsonOrNull!!

        assertEquals(2, body.get("compared")?.asInt(), "every attempt was compared, not the latest per student")
        val scores = body.get("scores")!!.asArray()
        assertEquals(1, scores.size(), "expected exactly the one cross-student pair, got ${scores.size()}")
        assertTrue(
            scores.get(0).get("similarity").asInt() > 90,
            "the real finding was lost: " + scores.get(0),
        )
    }

    @Test
    fun `a named submission from a course the caller did not name is refused, not answered`() {
        // The same exercise on a course this teacher does not teach. Access is asserted on the named
        // courses only, so the course filter in selectSubmissions is the one thing standing between
        // a teacher of course A and course B's names and full solutions for any id they can guess.
        val theirs = transaction {
            val courseB = Fixtures.course("Theirs")
            val courseExerciseB = Fixtures.courseExercise(courseB, myExercise)
            Fixtures.submission(courseExerciseB, Fixtures.student("their-student"), number = 1, solution = renamed)
        }

        val response = runWith(listOf(studentSubmission, theirs))

        assertEquals(400, response.status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", response.errorCode)
    }

    @Test
    fun `naming a submission the caller cannot see is refused without mailing ops`() {
        // A client naming the wrong id is not an operator alert, and every such refusal was mailed.
        // Driven through the controller directly, where the flag the handler reads is visible.
        val theirs = transaction {
            val courseB = Fixtures.course("Theirs")
            Fixtures.submission(
                Fixtures.courseExercise(courseB, myExercise), Fixtures.student("their-student"),
                number = 1, solution = renamed,
            )
        }
        val service = SimilarityService(
            maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000,
            timeoutSec = 120,
        )
        val controller = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        val request = TeacherCheckSimilarityController.Req(
            listOf(TeacherCheckSimilarityController.ReqCourse(myCourse.toString())),
            listOf(studentSubmission, theirs).map { TeacherCheckSimilarityController.ReqSubmission(it.toString()) },
        )

        val refused = assertThrows<InvalidRequestException> {
            controller.controller(myExercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER))
        }
        assertEquals(ReqError.ENTITY_WITH_ID_NOT_FOUND, refused.code)
        assertFalse(refused.notify, "a client's wrong id would be mailed to ops")
    }

    @Test
    fun `a submission named twice is compared once, not refused as missing`() {
        // The query answers a repeated id with one row, so the found-count check saw one fewer row
        // than ids and refused a submission that plainly exists.
        val response = runWith(listOf(studentSubmission, teacherSubmission, studentSubmission))

        assertEquals(200, response.status)
        assertEquals(2, response.jsonOrNull?.get("compared")?.asInt())
        assertEquals(1, response.elements("scores").size)
    }

    @Test
    fun `named submissions are compared as given, and a student's own attempts are never paired`() {
        // Explicit ids skip the latest-per-student selection, so the owner passed to the service is
        // all that keeps two attempts by one student from being reported against each other at 100%.
        val secondAttempt = transaction {
            Fixtures.submission(myCourseExercise, student, number = 2, solution = solution)
        }

        val response = runWith(listOf(studentSubmission, secondAttempt, teacherSubmission))

        assertEquals(200, response.status)
        assertEquals(3, response.jsonOrNull?.get("compared")?.asInt())
        val pairs = response.elements("scores").map { setOf(it.get("sub_1").asString(), it.get("sub_2").asString()) }
        assertEquals(
            setOf(
                setOf(studentSubmission.toString(), teacherSubmission.toString()),
                setOf(secondAttempt.toString(), teacherSubmission.toString()),
            ),
            pairs.toSet(),
            "expected each attempt against the other author only, got $pairs",
        )
    }

    @Test
    fun `the cap is checked on the named ids, before any of them is looked up`() {
        // Four ids against the test config's cap of three, three of which do not exist. Checked
        // first, the cap refuses them by count. Checked after loading — the order that puts every
        // named solution on the heap before refusing — the found-count check would answer first,
        // with ENTITY_WITH_ID_NOT_FOUND.
        val response = runWith(listOf(studentSubmission, 999_991L, 999_992L, 999_993L))

        assertEquals(400, response.status)
        assertEquals("TOO_MANY_SUBMISSIONS", response.errorCode)
    }

    @Test
    fun `a list of invalid ids is refused at its first violation, not collected in full`() {
        // Bean validation collected every violation, so a list of millions of blank ids built
        // millions of them, and a message out of all of them, before any cap could run. It fails fast.
        val response = api.post(
            "/v2/exercises/$myExercise/similarity",
            api.body(
                "courses" to listOf(mapOf("id" to myCourse.toString())),
                "submissions" to List(500) { mapOf("id" to "") },
            ),
            Auth.asTeacher(teacher),
        )

        assertEquals(400, response.status)
        assertEquals("INVALID_PARAMETER_VALUE", response.errorCode)
        val message = response.field("log_msg").orEmpty()
        assertEquals(1, Regex("'submissions\\[").findAll(message).count(), "violations: ${message.take(200)}")
    }

    @Test
    fun `a run past its budget is refused as SIMILARITY_TIMEOUT with the budget, not a 500`() {
        // The context's service has the test config's 120 s, which no fixture here can spend, so the
        // controller is driven directly over a service with one second, and a tokeniser slower than
        // that. Not a budget of none: that is now priced and refused before the run (see below), and the
        // timeout is still what an estimate that came out too low ends in. The mapping is under test.
        val clock = ManualTicker()
        val service = SimilarityService(
            maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000,
            timeoutSec = 1, ticker = clock,
        )
        service.tokenise = { language, id, source, maxTokens ->
            clock.advance(1_200)
            language.read(id, source, maxTokens)
        }
        val slow = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        val request = TeacherCheckSimilarityController.Req(
            listOf(TeacherCheckSimilarityController.ReqCourse(myCourse.toString())), null,
        )

        val refused = assertThrows<InvalidRequestException> {
            slow.controller(myExercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER))
        }
        assertEquals(ReqError.SIMILARITY_TIMEOUT, refused.code)
        assertEquals("1", refused.attributes.toMap()["budget_sec"])
    }

    @Test
    fun `a run whose pace says it cannot finish is refused as SIMILARITY_TOO_COSTLY, saying how long it would take`() {
        // Priced as fitting, then measured as it runs: ten pairs at 500 ms of the test's clock on one thread
        // against a budget of three seconds. The refusal is the estimate's, with its numbers, so the page says
        // the same either way. Every pair: optimised, JPlag now stops at the budget with what it found instead.
        fullMode()
        transaction {
            (1..3).forEach { n ->
                val other = "pace-$n"
                Fixtures.student(other)
                Fixtures.enrolStudent(myCourse, other)
                Fixtures.submission(myCourseExercise, other, number = 1, solution = solution + "\nprint($n)\n".repeat(n))
            }
        }
        val clock = ManualTicker()
        val service = SimilarityService(maxSubmissions = 10, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 3, ticker = clock)
        service.comparePair = { gst, first, second -> clock.advance(500); gst.compare(first, second) }
        service.threadsFor = { 1 }
        val slow = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        val request = TeacherCheckSimilarityController.Req(listOf(TeacherCheckSimilarityController.ReqCourse(myCourse.toString())), null)

        val refused = assertThrows<InvalidRequestException> {
            slow.controller(myExercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER))
        }
        assertEquals(ReqError.SIMILARITY_TOO_COSTLY, refused.code)
        val attributes = refused.attributes.toMap()
        assertEquals("3", attributes["budget_sec"])
        assertTrue(attributes.getValue("estimated_sec").toInt() >= 4, "estimated ${attributes["estimated_sec"]}s")
    }

    @Test
    fun `a cohort priced over the budget is refused before it runs, saying how long it would take`() {
        // Priced from sizes, before the permit: with no budget at all any cohort is over it, and the
        // refusal must come without one solution being tokenised - a run would only time out. Every pair, as
        // only that is refused: optimised, it would stop at the budget instead.
        fullMode()
        val tokenised = AtomicInteger()
        val service = SimilarityService(
            maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000,
            timeoutSec = 0,
        )
        service.tokenise = { language, id, source, maxTokens ->
            tokenised.incrementAndGet()
            language.read(id, source, maxTokens)
        }
        val noBudget = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        val request = TeacherCheckSimilarityController.Req(
            listOf(TeacherCheckSimilarityController.ReqCourse(myCourse.toString())), null,
        )

        val refused = assertThrows<InvalidRequestException> {
            noBudget.controller(myExercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER))
        }
        assertEquals(ReqError.SIMILARITY_TOO_COSTLY, refused.code)
        assertFalse(refused.notify, "a cohort too large for one check would be mailed to ops")
        val attributes = refused.attributes.toMap()
        assertEquals("0", attributes["budget_sec"])
        assertEquals("1", attributes["estimated_sec"])
        assertEquals(0, tokenised.get(), "the refused cohort was tokenised anyway")
    }

    @Test
    fun `the estimate prices the cohort the check would run, without running it`() {
        val response = api.post(
            "/v2/exercises/$myExercise/similarity/estimate",
            api.body("courses" to listOf(mapOf("id" to myCourse.toString()))),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, response.status)
        val json = response.jsonOrNull!!
        assertEquals(2, json.get("submissions").asInt())
        assertEquals(1, json.get("pairs").asInt())
        assertEquals("jplag", json.get("provider").asString())
        assertEquals("python", json.get("language").asString())
        assertEquals("optimised", json.get("mode").asString())
        assertTrue(json.get("provider_fallback").isNull)
        assertEquals(120, json.get("budget_sec").asInt())
        assertTrue(json.get("within_budget").asBoolean())
        assertTrue(json.get("estimated_sec").asInt() in 1..120, "estimated ${json.get("estimated_sec")}")
    }

    @Test
    fun `the estimate tells identical texts from texts that are only the same length`() {
        // Only submissions of equal length are hashed, since only those can be identical; one that is
        // merely the same length must not be grouped with the rest.
        val (exercise, copies, lookalike) = transaction {
            val exercise = Fixtures.exercise("Starter", teacher)
            val courseExercise = Fixtures.courseExercise(myCourse, exercise)
            val authors = (1..3).map { n -> Fixtures.student("copier-$n").also { Fixtures.enrolStudent(myCourse, it) } }
            val copies = authors.map { Fixtures.submission(courseExercise, it, number = 1, solution = "print(1)") }
            val lookalike = Fixtures.submission(courseExercise, authors[2], number = 2, solution = "print(2)")
            Triple(exercise, copies, lookalike)
        }
        fun pairs(ids: List<Long>) = api.post(
            "/v2/exercises/$exercise/similarity/estimate",
            api.body("courses" to listOf(mapOf("id" to myCourse.toString())), "submissions" to ids.map { mapOf("id" to it.toString()) }),
            Auth.asTeacher(teacher),
        ).jsonOrNull!!.get("pairs").asInt()
        // Three authors of one text are one comparand: nothing to compare among them.
        assertEquals(0, pairs(copies))
        // Two copies and a text of the same length by a third author: three comparands, three pairs. Taken
        // for a third copy, it would make three authors of one text, and no pair at all.
        assertEquals(3, pairs(copies.take(2) + lookalike))
    }

    @Test
    fun `the estimate refuses a named submission that is not there, as the check does`() {
        // Left out, the estimate answered for the rest and enabled a check the loader then refused.
        val (exercise, mine, elsewhere) = transaction {
            val exercise = Fixtures.exercise("Named", teacher)
            val courseExercise = Fixtures.courseExercise(myCourse, exercise)
            val other = Fixtures.courseExercise(myCourse, Fixtures.exercise("Other", teacher))
            val student = Fixtures.student("named-1").also { Fixtures.enrolStudent(myCourse, it) }
            Triple(
                exercise,
                Fixtures.submission(courseExercise, student, number = 1, solution = "print(1)"),
                Fixtures.submission(other, student, number = 1, solution = "print(2)"),
            )
        }
        fun estimate(ids: List<Long>) = api.post(
            "/v2/exercises/$exercise/similarity/estimate",
            api.body("courses" to listOf(mapOf("id" to myCourse.toString())), "submissions" to ids.map { mapOf("id" to it.toString()) }),
            Auth.asTeacher(teacher),
        )
        assertEquals(200, estimate(listOf(mine)).status)
        val refused = estimate(listOf(mine, elsewhere))
        assertEquals(400, refused.status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", refused.field("code"))
    }

    @Test
    fun `the estimate is refused as the check is, for a course the caller does not teach`() {
        val notMine = transaction { Fixtures.course("Not mine") }
        val response = api.post(
            "/v2/exercises/$myExercise/similarity/estimate",
            api.body("courses" to listOf(mapOf("id" to notMine.toString()))),
            Auth.asTeacher(teacher),
        )
        assertEquals(403, response.status)
    }

    @Test
    fun `a teacher may not name a course they do not teach`() {
        val notMine = transaction { Fixtures.course("Not mine") }
        assertEquals(403, run(myExercise, listOf(notMine)).status)
    }

    /** A program sharing nothing with [solution] but the language. */
    private val unrelated = """
        import math

        class Circle:
            def __init__(self, radius):
                self.radius = radius

            def area(self):
                return math.pi * self.radius ** 2
    """.trimIndent()

    @Test
    fun `the response carries both scores, the matched lines and exactly the submissions its pairs name`() {
        // Every other pair here is a pure rename, 100 on both scores, so the two could be swapped - and
        // the page would rank and filter on the average, burying the contained copy - with every test
        // green; nor did any read `submissions` or `matches`. A short solution pasted whole into a long
        // one tells the two scores apart, and an unrelated third is in the cohort but in no pair.
        val longer = solution + "\n\n" + unrelated.lines().drop(2).joinToString("\n")
        val (long, other) = transaction {
            val first = Fixtures.student("s-long")
            val second = Fixtures.student("s-other")
            Fixtures.enrolStudent(myCourse, first)
            Fixtures.enrolStudent(myCourse, second)
            Fixtures.submission(myCourseExercise, first, number = 1, solution = longer) to
                Fixtures.submission(myCourseExercise, second, number = 1, solution = "print(input())")
        }

        val response = runWith(listOf(studentSubmission, long, other))
        assertEquals(200, response.status)
        val scores = response.elements("scores")
        val contained = scores.single { setOf(it.get("sub_1").asString(), it.get("sub_2").asString()) == setOf("$studentSubmission", "$long") }
        val average = contained.get("similarity").asInt()
        val maximum = contained.get("max_similarity").asInt()
        assertTrue(maximum > 90 && average < maximum, "a contained copy should score high on the maximum only: $contained")

        val lines = mapOf("$studentSubmission" to solution.lines().size, "$long" to longer.lines().size)
        val matches = contained.get("matches").asArray()
        assertTrue(matches.size() > 0, "no matched region for a pair at $maximum%")
        for (match in matches) {
            for ((side, sub) in listOf("1" to contained.get("sub_1").asString(), "2" to contained.get("sub_2").asString())) {
                val start = match.get("start_line_$side").asInt()
                val end = match.get("end_line_$side").asInt()
                assertTrue(start in 1..end && end <= lines.getValue(sub), "a match outside submission $sub: $match")
            }
        }

        val referenced = scores.flatMap { listOf(it.get("sub_1").asString(), it.get("sub_2").asString()) }.toSet()
        val shipped = response.elements("submissions").map { it.get("id").asString() }.toSet()
        assertEquals(referenced, shipped, "the response should carry exactly the submissions its pairs name")
        assertFalse("$other" in shipped, "a submission in no pair was shipped")
    }

    @Test
    fun `identical submissions by three authors come back as one group, with every member named`() {
        // Two more authors hand in the student's program, one laid out differently, so the three are a
        // group: one entry instead of every pair among them, and the members shipped for the page to name.
        // (The teacher's is that program renamed, which is a pair, not identical code.)
        val (second, third) = transaction {
            listOf("s-second" to solution, "s-third" to solution.replace("\n", "\n\n")).map { (name, code) ->
                val author = Fixtures.student(name)
                Fixtures.enrolStudent(myCourse, author)
                Fixtures.submission(myCourseExercise, author, number = 1, solution = code)
            }
        }

        val response = runWith(listOf(studentSubmission, second, third))
        assertEquals(200, response.status)
        val groups = response.elements("groups").map { group -> group.get("submissions").toList().map { it.asString() } }
        assertEquals(listOf(listOf("$studentSubmission", "$second", "$third")), groups)
        assertEquals(0, response.elements("scores").size, "a pair inside the group was reported")
        // Every member named, but only the first with its code: the rest are that code but for layout.
        val shipped = response.elements("submissions").associate { it.get("id").asString() to it.get("solution") }
        assertEquals(setOf("$studentSubmission", "$second", "$third"), shipped.keys)
        assertEquals(solution, shipped.getValue("$studentSubmission").asString())
        assertTrue(shipped.getValue("$second").isNull && shipped.getValue("$third").isNull, "the group's code was sent per member: $shipped")
    }

    @Test
    fun `a malformed id is refused without mailing ops`() {
        // The id parser is shared by every endpoint, and refused with an operator alert: each request with
        // a non-numeric id also mailed ops a stack trace.
        val request = TeacherCheckSimilarityController.Req(listOf(TeacherCheckSimilarityController.ReqCourse("x")), null)
        val service = SimilarityService(maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 120)
        val controller = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        for ((exercise, body) in listOf("abc" to request, myExercise.toString() to request)) {
            val refused = assertThrows<InvalidRequestException> {
                controller.controller(exercise, body, Auth.easyUser(teacher, EasyRole.TEACHER))
            }
            assertEquals(ReqError.ENTITY_WITH_ID_NOT_FOUND, refused.code)
            assertFalse(refused.notify, "a malformed id would be mailed to ops")
        }
    }

    @Test
    fun `the latest attempt is the one compared, not the first`() {
        // The earlier fixture's attempts are all one solution, so the earliest and the latest compare
        // the same. Here the latest is another program, and it is what must be compared.
        val latest = transaction { Fixtures.submission(myCourseExercise, student, number = 2, solution = unrelated) }

        val response = run(myExercise, listOf(myCourse))
        assertEquals(200, response.status)
        assertEquals(2, response.jsonOrNull?.get("compared")?.asInt())
        val pair = response.elements("scores").singleOrNull()
        assertTrue(pair == null || pair.get("similarity").asInt() < 50, "the first attempt was compared instead of $latest: $pair")
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a caller that finds a comparison running and another waiting is refused as busy, without mailing ops`() {
        // One comparison runs at a time and one caller may wait for it; any further caller is refused at
        // once. The service maps that to SimilarityBusy, and the controller maps that to a named 400 -
        // which nothing tested: deleting the catch left a 500 and an ops mail for every busy click.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = SimilarityService(maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 120)
        service.tokenise = { language, id, source, maxTokens ->
            entered.countDown()
            release.await(30, TimeUnit.SECONDS)
            language.read(id, source, maxTokens)
        }
        val controller = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        val request = TeacherCheckSimilarityController.Req(
            listOf(TeacherCheckSimilarityController.ReqCourse(myCourse.toString())),
            listOf(studentSubmission, teacherSubmission).map { TeacherCheckSimilarityController.ReqSubmission(it.toString()) },
        )
        val call = { controller.controller(myExercise.toString(), request, Auth.easyUser(teacher, EasyRole.TEACHER)) }

        val running = thread { call() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the first comparison never started")
            val waiting = thread { call() }
            val deadline = System.nanoTime() + 10_000_000_000L
            while (waiting.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(Thread.State.TIMED_WAITING, waiting.state, "the second caller is not waiting for the permit")

            val refused = assertThrows<InvalidRequestException> { call() }
            assertEquals(ReqError.SIMILARITY_BUSY, refused.code)
            assertFalse(refused.notify, "a busy refusal would be mailed to ops")
            release.countDown()
            waiting.join(30_000)
        } finally {
            release.countDown()
            running.join(30_000)
        }
    }
}
