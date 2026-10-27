package core.ems.service.code

import core.conf.security.EasyRole
import core.db.CourseSimilaritySettings
import core.db.SimilarityLanguageSetting
import core.db.SimilarityProviderSetting
import core.ems.service.code.similarity.SimilarityLanguage
import core.ems.service.code.similarity.SimilarityService
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
    private var courseExercise = 0L

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
            courseExercise = Fixtures.courseExercise(course, exercise)
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
        // Optimised by default, and with one pair there is nothing to rule out.
        val json = response.jsonOrNull!!
        assertEquals(1, json.get("pairs_total").asInt())
        assertEquals(1, json.get("pairs_compared").asInt())
        assertTrue(json.get("complete").asBoolean())
    }

    @Test
    fun `with no budget even to prepare the cohort, the optimised mode is refused up front as the full one is`() {
        // Optimised stops at the budget with the pairs it reached - once the cohort is prepared. With no
        // budget at all nothing can be, so it is refused before the permit, as too costly, like the full
        // mode; let run, it held the permit to the deadline and timed out with nothing. That the optimised
        // mode is let run past a budget its preparing fits is SimilarityEstimateTest's and
        // OptimisedRankingTest's.
        val service = SimilarityService(maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 0)
        val controller = TeacherCheckSimilarityController(service, SimilarityRequests(service))
        val request = TeacherCheckSimilarityController.Req(
            listOf(TeacherCheckSimilarityController.ReqCourse(course.toString())),
            submissions.map { TeacherCheckSimilarityController.ReqSubmission(it.toString()) },
        )
        val caller = Auth.easyUser(teacher, EasyRole.TEACHER)
        assertEquals(ReqError.SIMILARITY_TOO_COSTLY, assertThrows<InvalidRequestException> { controller.controller(exercise.toString(), request, caller) }.code)
        assertEquals(200, putSettings(null, mode = "full").status)
        assertEquals(ReqError.SIMILARITY_TOO_COSTLY, assertThrows<InvalidRequestException> { controller.controller(exercise.toString(), request, caller) }.code)
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
        // On again is its default, so no row is left saying so.
        assertEquals(0L, transaction { SimilarityLanguageSetting.selectAll().count() })
    }

    private fun putSettings(provider: String?, mode: String = "optimised", courseId: Long = course, attempts: String? = null) =
        api.put(
            "/v2/courses/$courseId/similarity-settings",
            api.body(*listOfNotNull("provider" to provider, "mode" to mode, attempts?.let { "attempts" to it }).toTypedArray()),
            Auth.asTeacher(teacher),
        )

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
    fun `a provider saved at its defaults is no override, so it follows a later release's default`() {
        val overridden = { api.get("/v2/admin/similarity-providers", Auth.asAdmin(admin)).elements("providers").single { it.get("id").asString() == "jplag" }.get("overridden").asBoolean() }
        assertEquals(200, putProvider("jplag", enabled = true, priority = 200).status)
        assertTrue(overridden())
        // Restore defaults: on, at the default priority, no languages.
        assertEquals(200, putProvider("jplag", enabled = true, priority = 50).status)
        assertTrue(!overridden())
        assertEquals(0L, transaction { SimilarityProviderSetting.selectAll().count() })
        // Off at the default priority is an override still.
        assertEquals(200, putProvider("jplag", enabled = false, priority = 50).status)
        assertTrue(overridden())
    }

    @Test
    fun `a language a provider compares as text is listed as compared as text`() {
        // Without javac JPlag compares Java as text, as the estimate and the check say; the admin page said Java.
        val service = SimilarityService(maxSubmissions = 3, maxSolutionTokens = 10_000, maxRepetition = 1_000_000, timeoutSec = 60)
            .also { it.available = { language -> language != SimilarityLanguage.JAVA } }
        assertEquals(200, putProvider("jplag", enabled = true, priority = 200).status)
        val languages = AdminReadSimilarityProvidersController(service).controller(Auth.easyUser(admin, EasyRole.ADMIN)).languages
        val java = languages.single { it.language == "java" }
        assertEquals("jplag", java.provider)
        assertEquals("text", java.comparedAs)
        assertEquals("python", languages.single { it.language == "python" }.comparedAs)
    }

    private fun putExercise(
        ownProvider: Boolean,
        provider: String?,
        mode: String?,
        courseExerciseId: Long = courseExercise,
        courseId: Long = course,
        attempts: String? = null,
    ) = api.put(
        "/v2/courses/$courseId/similarity-settings/exercises/$courseExerciseId",
        api.body("own_provider" to ownProvider, "provider" to provider, "mode" to mode, "attempts" to attempts),
        Auth.asTeacher(teacher),
    )

    private fun estimate() = api.post(
        "/v2/exercises/$exercise/similarity/estimate",
        api.body("courses" to listOf(mapOf("id" to course.toString()))),
        Auth.asTeacher(teacher),
    ).jsonOrNull!!

    private fun exerciseOverrides() =
        api.get("/v2/courses/$course/similarity-settings", Auth.asTeacher(teacher)).elements("exercises")

    @Test
    fun `an exercise's own settings come before the course's, each on its own`() {
        assertEquals(200, putSettings("jplag", mode = "full").status)
        estimate().let {
            assertEquals("jplag", it.get("provider").asString())
            assertEquals("full", it.get("mode").asString())
            assertEquals("course", it.get("provider_from").asString())
            assertEquals("course", it.get("mode_from").asString())
        }
        // Its own mode only: the course's provider still holds.
        assertEquals(200, putExercise(ownProvider = false, provider = null, mode = "optimised").status)
        estimate().let {
            assertEquals("jplag", it.get("provider").asString())
            assertEquals("course", it.get("provider_from").asString())
            assertEquals("optimised", it.get("mode").asString())
            assertEquals("exercise", it.get("mode_from").asString())
        }
        // Its own provider too, and the check runs on it.
        assertEquals(200, putExercise(ownProvider = true, provider = "edit-distance", mode = "optimised").status)
        assertEquals("exercise", estimate().get("provider_from").asString())
        assertEquals("edit-distance", check().field("provider"))
        val listed = exerciseOverrides().single()
        assertEquals(courseExercise.toString(), listed.get("course_exercise_id").asString())
        assertEquals("edit-distance", listed.get("provider").asString())
    }

    @Test
    fun `an exercise may leave a course's pin for the admin's order`() {
        assertEquals(200, putProvider("jplag", enabled = true, priority = 10).status)
        assertEquals(200, putSettings("jplag").status)
        assertEquals("jplag", check().field("provider"))
        // Its own provider, none: the admin's order, where edit distance comes first.
        assertEquals(200, putExercise(ownProvider = true, provider = null, mode = null).status)
        assertEquals("edit-distance", check().field("provider"))
        assertEquals("exercise", estimate().get("provider_from").asString())
        assertEquals("course", estimate().get("mode_from").asString())
    }

    @Test
    fun `an exercise with nothing of its own follows the course's again, and is not listed`() {
        assertEquals(200, putSettings("jplag", mode = "full").status)
        assertEquals(200, putExercise(ownProvider = true, provider = "edit-distance", mode = "optimised").status)
        assertEquals(1, exerciseOverrides().size)
        // Neither its own is stored as nothing at all.
        assertEquals(200, putExercise(ownProvider = false, provider = "edit-distance", mode = null).status)
        assertEquals(0, exerciseOverrides().size)
        assertEquals("jplag", estimate().get("provider").asString())
        // Its own mode alone, then the course's again: cleared the same way.
        assertEquals(200, putExercise(ownProvider = false, provider = null, mode = "optimised").status)
        assertEquals("exercise", estimate().get("mode_from").asString())
        assertEquals(200, putExercise(ownProvider = false, provider = null, mode = null).status)
        assertEquals(0, exerciseOverrides().size)
        assertEquals("course", estimate().get("provider_from").asString())
        assertEquals("course", estimate().get("mode_from").asString())
        // With no course settings either, the defaults, and said so.
        transaction { CourseSimilaritySettings.deleteAll() }
        estimate().let {
            assertEquals("default", it.get("provider_from").asString())
            assertEquals("default", it.get("mode_from").asString())
        }
    }

    @Test
    fun `an exercise's settings are its course's teachers' to change, and only for an exercise on that course`() {
        val (other, otherExercise) = transaction {
            val other = Fixtures.course("Not mine")
            other to Fixtures.courseExercise(other, Fixtures.exercise("Elsewhere", teacher))
        }
        // Another course's exercise, named under this course: not found here, and not changed.
        val foreign = putExercise(ownProvider = false, provider = null, mode = "full", courseExerciseId = otherExercise)
        assertEquals(400, foreign.status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", foreign.field("code"))
        // A course the teacher does not teach.
        assertEquals(403, putExercise(ownProvider = false, provider = null, mode = "full", courseExerciseId = otherExercise, courseId = other).status)
        // A provider that is not installed, or switched off, and a mode that does not exist.
        assertEquals(400, putExercise(ownProvider = true, provider = "moss", mode = null).status)
        assertEquals(200, putProvider("jplag", enabled = false, priority = 50).status)
        assertEquals(400, putExercise(ownProvider = true, provider = "jplag", mode = null).status)
        assertEquals(400, putExercise(ownProvider = false, provider = null, mode = "everything").status)
        assertEquals(0, exerciseOverrides().size)
    }

    /** student-1 copies the [solution] student-2 handed in, then rewrites it: only the first attempt shows it. */
    private fun rewrittenCopy(): Long = transaction {
        Fixtures.submission(
            courseExercise, "student-1", number = 2,
            solution = """
                import collections

                class Counter:
                    def __init__(self):
                        self.seen = collections.defaultdict(int)
            """.trimIndent(),
        )
    }

    private fun checkNamed(ids: List<Long>) = api.post(
        "/v2/exercises/$exercise/similarity",
        api.body(
            "courses" to listOf(mapOf("id" to course.toString())),
            "submissions" to ids.map { mapOf("id" to it.toString()) },
        ),
        Auth.asTeacher(teacher),
    )

    /** What the page sends: the students it lists, for the attempts setting to choose among. */
    private fun checkStudents(students: List<String>, path: String = "", courseExercise: Long? = null, submissions: List<Long>? = null) = api.post(
        "/v2/exercises/$exercise/similarity$path",
        api.body(
            *listOfNotNull(
                "courses" to listOf(mapOf("id" to course.toString())),
                "students" to students.map { mapOf("id" to it) },
                courseExercise?.let { "course_exercise_id" to it.toString() },
                submissions?.let { ids -> "submissions" to ids.map { mapOf("id" to it.toString()) } },
            ).toTypedArray(),
        ),
        Auth.asTeacher(teacher),
    )

    @Test
    fun `with the first attempts, a copy later rewritten is found where the latest hide it`() {
        val latest = rewrittenCopy()
        // A third student the page does not name - a group chosen, say - who must not come in with them.
        transaction {
            Fixtures.student("student-3")
            Fixtures.enrolStudent(course, "student-3")
            Fixtures.submission(courseExercise, "student-3", number = 1, solution = "print(sum(map(int, input().split())))")
        }
        val page = listOf("student-1", "student-2")
        val latestRun = checkStudents(page).jsonOrNull!!
        assertEquals("latest", latestRun.get("attempts").asString())
        assertEquals(setOf(latest.toString(), submissions[1].toString()), latestRun.get("submissions").toList().map { it.get("id").asString() }.toSet())
        assertTrue(latestRun.get("scores").toList().none { it.get("max_similarity").asInt() > 50 }, "the rewrite still matched: $latestRun")

        assertEquals(200, putSettings(null, attempts = "first").status)
        val firstRun = checkStudents(page)
        assertEquals(200, firstRun.status)
        val json = firstRun.jsonOrNull!!
        assertEquals("first", json.get("attempts").asString())
        assertEquals(2, json.get("compared").asInt())
        assertEquals(100, json.get("scores").toList().single().get("max_similarity").asInt())
        // Compared and returned: student-1's first attempt, said to be one, and whose it is - the page's own
        // list knows each student by their latest, which this is not.
        val compared = json.get("submissions").toList().associate { it.get("id").asString() to (it.get("number").asInt() to it.get("student_id").asString()) }
        assertEquals(mapOf(submissions[0].toString() to (1 to "student-1"), submissions[1].toString() to (1 to "student-2")), compared)
        // Asked without students, every student's first attempt.
        val everyone = api.post(
            "/v2/exercises/$exercise/similarity",
            api.body("courses" to listOf(mapOf("id" to course.toString()))),
            Auth.asTeacher(teacher),
        ).jsonOrNull!!
        assertEquals(3, everyone.get("compared").asInt())
        assertEquals(100, everyone.get("scores").toList().maxOf { it.get("max_similarity").asInt() })
        estimate().let {
            assertEquals("first", it.get("attempts").asString())
            assertEquals("course", it.get("attempts_from").asString())
        }
        checkStudents(page, "/estimate").jsonOrNull!!.let {
            assertEquals(2, it.get("submissions").asInt())
            assertEquals("first", it.get("attempts").asString())
        }
    }

    @Test
    fun `named submissions are compared as named, whichever attempts the setting compares`() {
        // Under the first attempts each named submission was taken for its author and swapped for their
        // first, so a body naming two submissions compared two others, by a setting the caller may not
        // have seen. Named, they are what is compared, and the answer says so.
        val latest = rewrittenCopy()
        assertEquals(200, putSettings(null, attempts = "first").status)
        val response = checkNamed(listOf(latest, submissions[1]))
        assertEquals(200, response.status)
        val json = response.jsonOrNull!!
        assertEquals("named", json.get("attempts").asString())
        assertEquals(setOf(latest.toString(), submissions[1].toString()), json.get("submissions").toList().map { it.get("id").asString() }.toSet())
        assertTrue(json.get("scores").toList().none { it.get("max_similarity").asInt() > 50 }, "a first attempt was compared: $json")
        val estimated = api.post(
            "/v2/exercises/$exercise/similarity/estimate",
            api.body(
                "courses" to listOf(mapOf("id" to course.toString())),
                "submissions" to listOf(latest, submissions[1]).map { mapOf("id" to it.toString()) },
            ),
            Auth.asTeacher(teacher),
        ).jsonOrNull!!
        assertEquals("named", estimated.get("attempts").asString())
        assertEquals("request", estimated.get("attempts_from").asString())
    }

    @Test
    fun `an exercise can compare first attempts while its course compares the latest, and a save without it keeps the course's`() {
        rewrittenCopy()
        assertEquals(200, putSettings(null, attempts = "first").status)
        // Saved by a page that does not know of attempts: left as it was.
        assertEquals(200, putSettings("edit-distance").status)
        assertEquals("first", api.get("/v2/courses/$course/similarity-settings", Auth.asTeacher(teacher)).field("attempts"))
        assertEquals(200, putSettings(null, attempts = "latest").status)
        assertEquals(200, putExercise(ownProvider = false, provider = null, mode = null, attempts = "first").status)
        assertEquals("first", exerciseOverrides().single().get("attempts").asString())
        assertEquals("exercise", estimate().get("attempts_from").asString())
        assertEquals(100, checkStudents(listOf("student-1", "student-2")).elements("scores").single().get("max_similarity").asInt())
        // Attempts alone is a setting of its own: cleared, nothing is left.
        assertEquals(200, putExercise(ownProvider = false, provider = null, mode = null, attempts = null).status)
        assertEquals(0, exerciseOverrides().size)
        assertEquals(400, putSettings(null, attempts = "best").status)
        assertEquals(400, putExercise(ownProvider = false, provider = null, mode = null, attempts = "best").status)
    }

    @Test
    fun `a student on the exercise twice brings a submission from each, unless the request names the course exercise`() {
        // The exercise on the course twice, student-1 with an attempt under both: each is a course exercise
        // of its own, as the check without students has them, and student-1's two are never compared with
        // each other. The page lists one of them and links to it, so it names it, and the other's submissions
        // stay out: brought in, the count disagreed with the page's and their links opened another submission.
        val again = transaction {
            Fixtures.courseExercise(course, exercise).also { Fixtures.submission(it, "student-1", number = 1, solution = "print('elsewhere')") }
        }
        assertEquals(200, putSettings(null, attempts = "first").status)
        val both = checkStudents(listOf("student-1", "student-2")).jsonOrNull!!
        assertEquals(3, both.get("compared").asInt())
        assertEquals(2, both.get("pairs_total").asInt())

        val listed = checkStudents(listOf("student-1", "student-2"), courseExercise = courseExercise).jsonOrNull!!
        assertEquals(2, listed.get("compared").asInt())
        assertEquals(setOf(submissions[0].toString(), submissions[1].toString()), listed.get("submissions").toList().map { it.get("id").asString() }.toSet())
        assertEquals(2, checkStudents(listOf("student-1", "student-2"), "/estimate", courseExercise = courseExercise).jsonOrNull!!.get("submissions").asInt())
        // The other one's, named, has student-2 with nothing handed in.
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", checkStudents(listOf("student-1", "student-2"), courseExercise = again).field("code"))
        // One of another exercise is refused, not taken for an empty cohort.
        val elsewhere = transaction { Fixtures.courseExercise(course, Fixtures.exercise("Other", teacher)) }
        val refused = checkStudents(listOf("student-1", "student-2"), courseExercise = elsewhere)
        assertEquals(400, refused.status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", refused.field("code"))
    }

    @Test
    fun `a named student with nothing handed in here is refused, not dropped`() {
        assertEquals(200, putSettings(null, attempts = "first").status)
        transaction {
            Fixtures.student("student-3")
            Fixtures.enrolStudent(course, "student-3")
            val other = Fixtures.courseExercise(course, Fixtures.exercise("Other", teacher))
            Fixtures.submission(other, "student-3", number = 1)
        }
        val refused = checkStudents(listOf("student-1", "student-3"))
        assertEquals(400, refused.status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", refused.field("code"))
    }

    @Test
    fun `named with their latest submissions as well, the students decide, and more of them than the cap are refused unread`() {
        // The page sends the students' latest submissions beside them, for a core older than students, which
        // reads only those and so keeps the group filter. Here the students decide: under the first attempts,
        // their first is compared, not the latest named beside them.
        val latest = rewrittenCopy()
        assertEquals(200, putSettings(null, attempts = "first").status)
        val both = checkStudents(listOf("student-1", "student-2"), submissions = listOf(latest, submissions[1])).jsonOrNull!!
        assertEquals("first", both.get("attempts").asString())
        assertEquals(setOf(submissions[0].toString(), submissions[1].toString()), both.get("submissions").toList().map { it.get("id").asString() }.toSet())
        // Four against the test config's cap of three, two of whom do not exist: refused by count, not
        // looked up and refused as missing.
        val tooMany = checkStudents(listOf("student-1", "student-2", "nobody-1", "nobody-2"))
        assertEquals(400, tooMany.status)
        assertEquals("TOO_MANY_SUBMISSIONS", tooMany.field("code"))
        // Named twice, a student is one.
        assertEquals(1, checkStudents(listOf("student-1", "student-2", "student-1")).jsonOrNull!!.get("scores").toList().size)
    }

    @Test
    fun `the admin page counts the courses and exercises that chose each provider, which switching it off moves`() {
        assertEquals(200, putSettings("jplag").status)
        assertEquals(200, putExercise(ownProvider = true, provider = "edit-distance", mode = null).status)
        // An exercise following the course's choice has chosen nothing of its own.
        transaction { Fixtures.courseExercise(course, Fixtures.exercise("Other", teacher)) }
        val providers = api.get("/v2/admin/similarity-providers", Auth.asAdmin(admin)).elements("providers").associateBy { it.get("id").asString() }
        assertEquals(1, providers.getValue("jplag").get("pinned_courses").asInt())
        assertEquals(0, providers.getValue("jplag").get("pinned_exercises").asInt())
        assertEquals(0, providers.getValue("edit-distance").get("pinned_courses").asInt())
        assertEquals(1, providers.getValue("edit-distance").get("pinned_exercises").asInt())
    }

    @Test
    fun `providers are code, so an unknown one or a language it does not compare is refused`() {
        assertEquals(400, putProvider("moss", enabled = true, priority = 10).status)
        assertEquals(400, putProvider("jplag", enabled = true, priority = 10, languages = listOf("cobol" to true)).status)
        assertEquals(400, putLanguage("cobol", enabled = false).status)
    }
}
