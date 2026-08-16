package core.ems.service.article

import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc

/**
 * The article endpoints, end to end: filter chain, Jackson, the Markdown renderer, the cache and the
 * database.
 *
 * **This replaces `doc/core/articles-check.sh`, which is deleted in the same commit.** That script
 * said, in its own header, that it existed only because CI had no database and that EZ-1715 was the
 * condition for porting it — that condition is met, so keeping both would leave two specifications
 * of the same rules with only one of them ever run. Its seven sections are the seven groups below,
 * in the same order and with the same names, so the port is checkable by reading them side by side.
 *
 * Three things are covered here that the script could not reach, all of them cache behaviour: a
 * signed-in non-admin and an anonymous caller must get **byte-identical** payloads, an admin
 * reading first must not leave the source in the entry the next anonymous caller reads, and a
 * publish must be visible immediately. The script ran against a long-lived core where warm entries
 * were indistinguishable from correct answers.
 *
 * ### The rule the first group is about
 *
 * A draft answers exactly as a nonexistent article does — same code, same status — and it does so
 * because the visibility rule is a `where` clause in `CachingService.selectLatestArticleVersion`
 * rather than a check after the fact. That is worth a test rather than trusting the comment: aliases
 * are short guessable words on an endpoint reachable with no account, so a distinguishable
 * "forbidden" would make the alias namespace enumerable, and for a draft the title is usually the
 * whole of the secret.
 */
@IntegrationTest
class ArticleApiTest(@Autowired mockMvc: MockMvc) {

    private val api = HttpApi(mockMvc)

    private val admin = Auth.ADMIN_ID
    private val student = Auth.STUDENT_ID
    private val teacher = Auth.TEACHER_ID

    private var published = ""
    private var draft = ""

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin, givenName = "Ada", familyName = "Admin")
            Fixtures.student(student)
            Fixtures.teacher(teacher)
        }
        published = createArticle("Check published", published = true)
        draft = createArticle("Check draft", published = false)
    }

    private fun createArticle(title: String, published: Boolean): String {
        val resp = api.post(
            "/v2/articles",
            api.body("title" to title, "text_md" to "Hello **world**", "published" to published),
            Auth.asAdmin(admin),
        )
        assertEquals(200, resp.status) { "Could not create the fixture article: ${resp.body}" }
        return resp.field("id")!!
    }

    // --- 1. a draft is admin-only ----------------------------------------------------------------

    @Test
    fun `a draft is refused to a student and to a teacher, and answers as a missing id does`() {
        val missing = api.get("/v2/articles/99999999", Auth.asStudent(student))

        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.get("/v2/articles/$draft", Auth.asStudent(student)).errorCode)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.get("/v2/articles/$draft", Auth.asTeacher(teacher)).errorCode)

        // The point of filtering in the query rather than throwing after a lookup: one code path, so
        // these two answers cannot drift. If they ever differ, a draft has become detectable.
        val asDraft = api.get("/v2/articles/$draft", Auth.asStudent(student))
        assertEquals(missing.errorCode, asDraft.errorCode)
        assertEquals(missing.status, asDraft.status)
    }

    @Test
    fun `an admin reads a draft, and anyone signed in reads a published one`() {
        assertEquals(200, api.get("/v2/articles/$draft", Auth.asAdmin(admin)).status)
        assertEquals(200, api.get("/v2/articles/$published", Auth.asStudent(student)).status)
    }

    // --- 2. no account at all --------------------------------------------------------------------

    @Test
    fun `a published article is readable with no account, and a draft is not`() {
        assertEquals(200, api.get("/v2/unauth/articles/$published", api.anonymous()).status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.get("/v2/unauth/articles/$draft", api.anonymous()).errorCode)
    }

    @Test
    fun `and the authenticated route still refuses an anonymous caller`() {
        // The public route is `/unauth/...` and only that one. If `/v2/articles/{id}` ever answered
        // anonymously, the permitAll list would have grown a pattern broader than it looks.
        assertEquals(401, api.get("/v2/articles/$published", api.anonymous()).status)
    }

    // --- 3. the public payload -------------------------------------------------------------------

    @Test
    fun `the anonymous payload carries the rendered html and neither the source nor a username`() {
        val anon = api.get("/v2/unauth/articles/$published", api.anonymous()).jsonOrNull!!

        assertFalse(anon.has("text_md")) { "The Markdown source reached an anonymous reader" }
        assertFalse(anon.has("published")) { "The published flag reached an anonymous reader" }
        assertFalse(anon.get("author").has("id")) { "A username reached an anonymous reader" }

        // The byline is the part that must survive: an article with no author name renders as
        // written by nobody, which is a worse bug than it sounds for a page that is the FAQ.
        assertEquals("Ada", anon.get("author").get("given_name").asString())
        assertTrue(anon.get("text_html").asString().contains("<strong>")) {
            "Markdown was not rendered: ${anon.get("text_html")}"
        }
    }

    @Test
    fun `an admin still gets the source`() {
        val asAdmin = api.get("/v2/articles/$published", Auth.asAdmin(admin)).jsonOrNull!!
        assertEquals("Hello **world**", asAdmin.get("text_md").asString())
        assertTrue(asAdmin.get("published").asBoolean())
    }

    /**
     * The two callers that share a cache entry must get the same bytes.
     *
     * `selectLatestArticleVersion` is `@Cacheable` on `(idOrAlias, isAdmin)`, and its docblock says
     * anonymous and signed-in-non-admin are deliberately the same entry. That is only safe while
     * their payloads are identical — otherwise whichever caller populates the entry decides what the
     * other one sees, and **request order becomes the access control**. Nothing about that failure
     * is loud; it is two correct-looking responses that happen to disagree.
     */
    @Test
    fun `a signed-in non-admin and an anonymous caller get byte-identical payloads`() {
        val signedIn = api.get("/v2/articles/$published", Auth.asStudent(student))
        val anonymous = api.get("/v2/unauth/articles/$published", api.anonymous())

        assertEquals(200, signedIn.status)
        assertEquals(200, anonymous.status)
        assertEquals(signedIn.body, anonymous.body)
    }

    /**
     * And the admin entry must not be the one an anonymous caller reads.
     *
     * The order is the whole test: the admin call **first**, so the cache is warm with the payload
     * that contains `text_md`, the owner's username and the draft flag. If `isAdmin` were ever
     * dropped from the cache key — a plausible tidy-up, since it is not part of the article's
     * identity — this is what would leak, and every other assertion in this file would still pass.
     */
    @Test
    fun `an admin reading first does not leave the source in the entry an anonymous caller reads`() {
        val asAdmin = api.get("/v2/articles/$published", Auth.asAdmin(admin))
        assertTrue(asAdmin.jsonOrNull!!.has("text_md")) { "Precondition: the admin payload has the source" }

        val anon = api.get("/v2/unauth/articles/$published", api.anonymous()).jsonOrNull!!
        assertFalse(anon.has("text_md")) { "The admin's cache entry was served to an anonymous caller" }
        assertNotEquals(asAdmin.body, api.get("/v2/unauth/articles/$published", api.anonymous()).body)
    }

    /**
     * Publishing takes effect at once.
     *
     * Every write path calls `invalidate(articleCache)`; forgetting one leaves a draft answering
     * `ENTITY_WITH_ID_NOT_FOUND` to the public after it has been published, which reads as "the link
     * is broken" rather than as a cache bug and is the sort of thing that survives for weeks.
     */
    @Test
    fun `publishing a draft is visible to the public immediately`() {
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.get("/v2/unauth/articles/$draft", api.anonymous()).errorCode)

        val update = api.put(
            "/v2/articles/$draft",
            api.body("title" to "Check draft", "text_md" to "Hello **world**", "published" to true),
            Auth.asAdmin(admin),
        )
        assertEquals(200, update.status) { update.body }

        assertEquals(200, api.get("/v2/unauth/articles/$draft", api.anonymous()).status)
    }

    // --- 4. the listing is admin-only ------------------------------------------------------------

    @Test
    fun `the listing is admin-only and carries drafts`() {
        assertEquals(403, api.get("/v2/articles", Auth.asTeacher(teacher)).status)
        assertEquals(403, api.get("/v2/articles", Auth.asStudent(student)).status)

        val listing = api.get("/v2/articles", Auth.asAdmin(admin))
        assertEquals(200, listing.status)

        val articles = listing.elements("articles")
        assertEquals(2, articles.size)
        // The index is where an admin finds a draft to finish, so a listing that quietly filtered
        // on published would make drafts unreachable through the UI entirely.
        assertTrue(articles.any { !it.get("published").asBoolean() }) { "No draft in the listing: ${listing.body}" }
    }

    // --- 5. a PUT cannot blank an article --------------------------------------------------------

    @Test
    fun `an update without text_md is refused, and the text is still there afterwards`() {
        // text_html is regenerated from text_md on every save, so accepting this would empty the
        // article — and the old text would survive only in a version history no endpoint exposes.
        val blanking = api.put(
            "/v2/articles/$published",
            api.body("title" to "Check published", "published" to true),
            Auth.asAdmin(admin),
        )
        assertEquals("INVALID_PARAMETER_VALUE", blanking.errorCode)

        val after = api.get("/v2/articles/$published", Auth.asAdmin(admin)).jsonOrNull!!
        assertTrue(after.get("text_html").asString().contains("<strong>")) { "The article was blanked anyway" }
    }

    // --- 6. aliases ------------------------------------------------------------------------------

    @Test
    fun `a hyphenated alias is accepted and resolves anonymously`() {
        assertEquals(
            200,
            api.post("/v2/articles/$published/aliases", api.body("alias" to "check-how-to"), Auth.asAdmin(admin)).status,
        )

        val byAlias = api.get("/v2/unauth/articles/check-how-to", api.anonymous())
        assertEquals(200, byAlias.status)
        // Resolved to the article, not merely to *an* article: the alias table is a second lookup
        // path into the same row, and one that returned the wrong row would still be a 200.
        assertEquals(published, byAlias.field("id"))
    }

    @Test
    fun `an all-digit alias is refused`() {
        // EZ-1762. The read path resolves an alias first and falls back to parsing the segment as a
        // numeric id, so an all-digit alias would shadow the article whose id it matches. Requiring
        // a letter keeps the two namespaces disjoint, so the lookup order cannot matter.
        assertEquals(
            400,
            api.post("/v2/articles/$published/aliases", api.body("alias" to "2023"), Auth.asAdmin(admin)).status,
        )
    }

    @Test
    fun `an alias does not resolve to a draft`() {
        // The alias lookup is deliberately *not* filtered on published — it resolves to an id and
        // the main query then finds nothing. Worth pinning: it is the one place where the visibility
        // rule could plausibly be argued to belong, and putting it there would be a second copy.
        assertEquals(
            200,
            api.post("/v2/articles/$draft/aliases", api.body("alias" to "secret-draft"), Auth.asAdmin(admin)).status,
        )
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.get("/v2/unauth/articles/secret-draft", api.anonymous()).errorCode)
    }

    // --- 7. delete -------------------------------------------------------------------------------

    @Test
    fun `a published article cannot be deleted until it is unpublished`() {
        assertEquals("ARTICLE_PUBLISHED", api.delete("/v2/articles/$published", Auth.asAdmin(admin)).errorCode)

        val unpublish = api.put(
            "/v2/articles/$published",
            api.body("title" to "Check published", "text_md" to "x", "published" to false),
            Auth.asAdmin(admin),
        )
        assertEquals(200, unpublish.status) { unpublish.body }

        assertEquals(200, api.delete("/v2/articles/$published", Auth.asAdmin(admin)).status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.get("/v2/articles/$published", Auth.asAdmin(admin)).errorCode)
    }

    @Test
    fun `deleting an article frees its alias for reuse`() {
        api.post("/v2/articles/$published/aliases", api.body("alias" to "check-how-to"), Auth.asAdmin(admin))
        api.put(
            "/v2/articles/$published",
            api.body("title" to "Check published", "text_md" to "x", "published" to false),
            Auth.asAdmin(admin),
        )
        assertEquals(200, api.delete("/v2/articles/$published", Auth.asAdmin(admin)).status)

        // An alias left behind would be unusable forever and would still resolve — to a row that is
        // gone. The delete takes the aliases with it, and the reuse is the observable proof.
        assertEquals(
            200,
            api.post("/v2/articles/$draft/aliases", api.body("alias" to "check-how-to"), Auth.asAdmin(admin)).status,
        )
    }

    @Test
    fun `a deleted article is gone from the listing as well as by id`() {
        // The listing has its own cache entry in the same region. Both are invalidated by the same
        // call today; a deleted article still appearing in the index is what it looks like when one
        // of them stops being.
        api.put(
            "/v2/articles/$published",
            api.body("title" to "Check published", "text_md" to "x", "published" to false),
            Auth.asAdmin(admin),
        )
        api.delete("/v2/articles/$published", Auth.asAdmin(admin))

        val ids = api.get("/v2/articles", Auth.asAdmin(admin)).elements("articles").map { it.get("id").asString() }
        assertEquals(listOf(draft), ids)
    }

    @Test
    fun `only an admin may write`() {
        val body = api.body("title" to "Nope", "text_md" to "x", "published" to false)

        assertEquals(403, api.post("/v2/articles", body, Auth.asTeacher(teacher)).status)
        assertEquals(403, api.put("/v2/articles/$published", body, Auth.asTeacher(teacher)).status)
        assertEquals(403, api.delete("/v2/articles/$draft", Auth.asTeacher(teacher)).status)
        assertEquals(
            403,
            api.post("/v2/articles/$published/aliases", api.body("alias" to "nope"), Auth.asStudent(student)).status,
        )

        // And nothing happened: a 403 that arrives after the write is a 403 that protects nothing.
        // Asserted on the *listing*, not on reading back the alias the student tried to create — an
        // error body carries an `id` of its own, so "an id came back" is not evidence either way.
        val remaining = api.get("/v2/articles", Auth.asAdmin(admin)).elements("articles")
        assertEquals(setOf(published, draft), remaining.map { it.get("id").asString() }.toSet())
        assertTrue(remaining.none { it.get("aliases").toList().isNotEmpty() }) { "An alias was created anyway" }
    }
}
