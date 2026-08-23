package core.ems.service.file

import core.ems.service.storage.StorageService
import core.testing.Auth
import core.testing.Fixtures
import core.testing.HttpApi
import core.testing.IntegrationTest
import core.testing.TestClock
import jakarta.servlet.http.HttpServletResponse
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The file endpoints, end to end: upload, serve, list, mark, delete.
 *
 * **This replaces `doc/core/files-check.sh`, which is deleted in the same commit** — for the reason
 * its own header gave, that a script only exists while CI has no database. Its eight sections are
 * the groups below, in order.
 *
 * ### What the port had to keep, and how it keeps it
 *
 * The script ran against *whichever backend the core it was pointed at happened to use*, and
 * asserted the shape that backend produced — a 200 with bytes for local, a 302 to a public object
 * for S3. That is the part worth preserving, because production runs S3 and a suite that only ever
 * exercises local is coverage of the wrong thing.
 *
 * It is preserved in two pieces rather than one, because the two halves fail for different reasons:
 *
 * - **The endpoint's branch** — redirect when the backend has a public URL, stream when it does not
 *   — is exercised here, both ways, in `serving`. The S3 leg drives the controller with a stub
 *   backend rather than a real bucket, because what is being tested is `if (publicUrl != null)` and
 *   the headers on either side of it. A MinIO container would test the same three lines while adding
 *   a Docker dependency to the leg that has none.
 * - **The backend semantics** — put/get/list/delete, key validation, idempotency — are exercised
 *   against *both real implementations* in `StorageServiceContractTest`, which does start MinIO.
 *
 * Splitting it that way is also the only way to have both without forking the Spring context.
 * `S3StorageService` is `@ConditionalOnProperty`, so a second backend inside `@IntegrationTest`
 * would mean a second context and ten more seconds on every run — see the rule in
 * `core/testing/IntegrationTest.kt`.
 */
@IntegrationTest
class FileApiTest(@Autowired mockMvc: MockMvc, @Autowired private val storageService: StorageService) {

    private val api = HttpApi(mockMvc)

    private val admin = Auth.ADMIN_ID
    private val teacher = Auth.TEACHER_ID
    private val student = Auth.STUDENT_ID

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin)
            Fixtures.teacher(teacher)
            Fixtures.student(student)
        }
    }

    private fun upload(
        filename: String = "pixel.png",
        content: ByteArray = PIXEL_PNG,
        contentType: String = "image/png",
    ) = api.upload("/v2/files", "file", filename, contentType, content, Auth.asTeacher(teacher))

    /**
     * Upload, and return the key — after checking the upload succeeded.
     *
     * The status check is not ceremony. `Response.field` reads `id` off an *error* body just as
     * readily as a success one, because `RequestErrorResponse` carries a correlation id under that
     * name; without this, a rejected upload hands back a plausible-looking key and every downstream
     * assertion fails as a confusing 404 on something that never existed.
     */
    private fun uploadedKey(): String {
        val resp = upload()
        assertEquals(200, resp.status) { "Upload failed: ${resp.body}" }
        return resp.field("id")!!
    }

    // --- 0. what the upload tells the caller -----------------------------------------------------

    /**
     * The id alone cannot build a URL.
     *
     * The filename is sanitised server-side and the type is **sniffed from the content**, so a
     * caller that guessed either would be wrong in exactly the cases that matter — which is why the
     * response carries all three.
     */
    @Test
    fun `the upload returns the sanitised filename and the sniffed type`() {
        val resp = upload()
        assertEquals(200, resp.status) { resp.body }
        assertEquals("pixel.png", resp.field("filename"))
        assertEquals("image/png", resp.field("mime_type"))
        assertEquals(27, resp.field("id")!!.length) { "Not a storage key: ${resp.field("id")}" }
    }

    @Test
    fun `the type is sniffed rather than believed`() {
        // A PNG announced as a PDF. Trusting the client here would mean serving a Content-Type to
        // the internet on an uploader's say-so — and, since the disposition policy keys off the
        // type, would let an HTML file claim to be a PNG and render.
        val resp = upload(filename = "lies.pdf", contentType = "application/pdf")
        assertEquals("image/png", resp.field("mime_type"))
    }

    @Test
    fun `a filename with a path and a quote in it is stripped before it reaches a header`() {
        // Header injection, handed to any teacher: the raw name used to be interpolated into
        // Content-Disposition. Path separators go too — the name is the last segment of a URL and
        // has no business having depth.
        val resp = upload(filename = "../../etc/pa\"ss wd.png")
        assertEquals("pass wd.png", resp.field("filename"))
    }

    // --- 1. reading it, with no account at all ---------------------------------------------------

    @Test
    fun `an uploaded file is served to a caller with no account, and to one with`() {
        val key = uploadedKey()

        val anonymous = api.get("/v2/resource/$key/pixel.png", api.anonymous())
        assertEquals(200, anonymous.status)
        // The bytes, not merely a 200 with something in it. Asserted on the whole array rather than
        // on a signature: the point of this endpoint is that what was uploaded is what comes back.
        assertArrayEquals(PIXEL_PNG, anonymous.bytes, "The bytes did not come back")
        assertEquals("image/png", anonymous.header("Content-Type"))
        assertEquals(PIXEL_PNG.size.toString(), anonymous.header("Content-Length"))

        assertEquals(200, api.get("/v2/resource/$key/pixel.png", Auth.asStudent(student)).status)
    }

    /**
     * The header that makes this design cheap, and the note attached to it.
     *
     * A key is minted once and its bytes never change, so the URL can be cached for a year. **If
     * this ever becomes a redirect to a *signed* URL that year has to go with it** — a year-long
     * cache of a ten-minute URL is a broken image for the rest of the year.
     */
    @Test
    fun `the response is cacheable forever`() {
        val key = uploadedKey()
        assertEquals(
            "public, max-age=31536000, immutable",
            api.get("/v2/resource/$key/pixel.png", api.anonymous()).header("Cache-Control"),
        )
    }

    /**
     * The S3 leg of the serving branch, driven directly.
     *
     * The controller is a plain class over a `StorageService`, so a backend that reports a public
     * URL is one object away — no bucket, no container, no second Spring context. What is asserted
     * is what the script asserted when it found itself pointed at an S3 core: a 302, a `Location`
     * off this host, and the same immutable cache header on the redirect itself.
     */
    @Test
    fun `a backend with a public url redirects instead of streaming`() {
        val key = uploadedKey()
        val response = MockHttpServletResponse()

        ReadStoredFileController(PublicUrlBackend).controller(key, "pixel.png", response)

        assertEquals(HttpServletResponse.SC_FOUND, response.status)
        assertEquals("https://bucket.example/$key", response.getHeader("Location"))
        assertEquals("public, max-age=31536000, immutable", response.getHeader("Cache-Control"))
        assertEquals(0, response.contentAsByteArray.size) { "A redirect must not also stream the bytes" }
    }

    @Test
    fun `a redirecting backend still refuses a key with no row`() {
        // The row is what proves the file exists; the object store is asked nothing. A backend that
        // answers a URL for every key — which is what publicUrl does, it is string concatenation —
        // must not turn an unknown key into a redirect to a 404 on someone else's domain.
        val response = MockHttpServletResponse()
        ReadStoredFileController(PublicUrlBackend).controller("a".repeat(27), "x.png", response)
        assertEquals(404, response.status)
    }

    // --- 1b. what may render in a browser, and what may only download ----------------------------

    /**
     * Objects are public and served from the store's own origin, so an uploaded page is a working
     * page on a domain we do not vouch for. SVG is the same class for a less obvious reason: safe
     * inside `<img>`, scriptable when navigated to.
     */
    @Test
    fun `a png renders inline, and html and svg are forced to download`() {
        val png = uploadedKey()
        val svg = upload("x.svg", """<svg xmlns="http://www.w3.org/2000/svg"><script/></svg>""".toByteArray()).field("id")!!
        val html = upload("x.html", "<html><body><h1>not ours</h1></body></html>".toByteArray()).field("id")!!

        assertTrue(disposition(png, "pixel.png").startsWith("inline")) { disposition(png, "pixel.png") }
        assertTrue(disposition(svg, "x.svg").startsWith("attachment")) { disposition(svg, "x.svg") }
        assertTrue(disposition(html, "x.html").startsWith("attachment")) { disposition(html, "x.html") }
    }

    /**
     * XHTML is the case that showed the deny list was the wrong shape.
     *
     * It was not in the two-element `MUST_DOWNLOAD` set, browsers render it natively, and `<script>`
     * inside it runs. Nothing about the file has to lie: Tika detects `application/xhtml+xml` from
     * the namespace and from the literal `<html xmlns=`, so the extension is irrelevant. And the
     * old comment's reason for tolerating a rendered upload — "different origin, no cookies, no
     * session" — is true of the S3 backend and false of the local one, which streams through core and
     * therefore through the web origin. `local` is the default and what production runs.
     */
    @Test
    fun `xhtml is forced to download, whatever it is called`() {
        val xhtml = """<html xmlns="http://www.w3.org/1999/xhtml"><script>alert(1)</script></html>"""
        val key = upload("notes.txt", xhtml.toByteArray()).field("id")!!

        assertTrue(disposition(key, "notes.txt").startsWith("attachment")) { disposition(key, "notes.txt") }
    }

    @Test
    fun `an unrecognised type downloads, because the policy is now an allow list`() {
        // The direction that matters. Under the old deny list anything not named in it rendered, so
        // every type nobody had thought about — including the next one a browser learns to execute —
        // defaulted to inline. Now the default is download and the exceptions are enumerated.
        val key = upload("thing.bin", byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)).field("id")!!

        assertTrue(disposition(key, "thing.bin").startsWith("attachment")) { disposition(key, "thing.bin") }
    }

    @Test
    fun `pdf still previews, because that is worth something and it has no DOM`() {
        val key = upload("notes.pdf", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n".toByteArray()).field("id")!!

        assertTrue(disposition(key, "notes.pdf").startsWith("inline")) { disposition(key, "notes.pdf") }
    }

    @Test
    fun `the response says not to sniff it`() {
        // On the response rather than left to the vhost: this location is proxied from the web origin,
        // and whether nginx's `add_header` reaches a proxied response depends on the block it lands
        // in. The header that stops a browser re-deciding what a file is belongs next to the
        // Content-Type it protects.
        val key = uploadedKey()
        assertEquals("nosniff", api.get("/v2/resource/$key/pixel.png", api.anonymous()).header("X-Content-Type-Options"))
    }

    @Test
    fun `the disposition carries the sanitised filename, not the key`() {
        val key = upload(filename = "my diagram.png").field("id")!!
        assertEquals("""inline; filename="my diagram.png"""", disposition(key, "my diagram.png"))
    }

    private fun disposition(key: String, filename: String) =
        api.get("/v2/resource/$key/$filename", api.anonymous()).header("Content-Disposition").orEmpty()

    // --- 2. the filename is decoration -----------------------------------------------------------

    @Test
    fun `a different filename in the url still resolves`() {
        // Renaming a file must not break a URL already embedded in a stored article, which is why
        // the filename is never looked up.
        val key = uploadedKey()
        assertEquals(200, api.get("/v2/resource/$key/something-else.png", api.anonymous()).status)
    }

    // --- 3. keys that are not keys ---------------------------------------------------------------

    @Test
    fun `an unknown but well-formed key, and a malformed one, both 404`() {
        assertEquals(404, api.get("/v2/resource/${"a".repeat(27)}/x.png", api.anonymous()).status)
        assertEquals(404, api.get("/v2/resource/short/x.png", api.anonymous()).status)
        assertEquals(404, api.get("/v2/resource/${"a".repeat(28)}/x.png", api.anonymous()).status)
    }

    @Test
    fun `a deeper path is not public`() {
        // The permitAll pattern is `/*/resource/*/*` — two segments, deliberately, so that anything
        // deeper falls through to anyRequest().authenticated() and fails closed. A `/**` there would
        // publish whatever anyone later mounts under /v2/resource.
        assertEquals(401, api.get("/v2/resource/${"a".repeat(27)}/nested/x.png", api.anonymous()).status)
    }

    // --- 4. metadata -----------------------------------------------------------------------------

    @Test
    fun `the metadata listing is admin-only and describes the file`() {
        val key = uploadedKey()

        assertEquals(403, api.get("/v2/files/metadata", Auth.asTeacher(teacher)).status)
        assertEquals(403, api.get("/v2/files/metadata", Auth.asStudent(student)).status)

        val listing = api.get("/v2/files/metadata", Auth.asAdmin(admin))
        assertEquals(200, listing.status)

        val file = listing.elements("files").single { it.get("id").asString() == key }
        assertEquals("image/png", file.get("mime_type").asString())
        assertEquals(PIXEL_PNG.size.toLong(), file.get("size_bytes").asLong())
        assertEquals(teacher, file.get("created_by").asString())
        assertFalse(file.get("persistent").asBoolean()) { "A new upload must not start persistent" }

        // The bytea era left `exercise_id`, `article_id` and `data` on this table. Nothing points
        // anywhere any more — the sweep decides what is garbage — and a field reappearing here
        // would mean that decision had been quietly reversed.
        listOf("exercise_id", "article_id", "data").forEach {
            assertFalse(file.has(it)) { "The bytea-era field '$it' is back on the metadata payload" }
        }
    }

    // --- 5. persistent ---------------------------------------------------------------------------

    @Test
    fun `only an admin may mark a file persistent, and the filter finds it`() {
        val key = uploadedKey()

        assertEquals(403, api.put("/v2/files/$key", api.body("persistent" to true), Auth.asTeacher(teacher)).status)
        assertEquals(200, api.put("/v2/files/$key", api.body("persistent" to true), Auth.asAdmin(admin)).status)

        assertTrue(idsWithFilter("?persistent=true").contains(key))
        assertFalse(idsWithFilter("?persistent=false").contains(key)) { "Marked, yet listed as not persistent" }

        assertEquals(200, api.put("/v2/files/$key", api.body("persistent" to false), Auth.asAdmin(admin)).status)
        assertFalse(idsWithFilter("?persistent=true").contains(key))
        assertTrue(idsWithFilter("?persistent=false").contains(key))
    }

    @Test
    fun `marking a file that does not exist is reported rather than silently doing nothing`() {
        assertEquals(
            "ENTITY_WITH_ID_NOT_FOUND",
            api.put("/v2/files/${"a".repeat(27)}", api.body("persistent" to true), Auth.asAdmin(admin)).errorCode,
        )
    }

    private fun idsWithFilter(query: String) =
        api.get("/v2/files/metadata$query", Auth.asAdmin(admin)).elements("files").map { it.get("id").asString() }

    // --- 6. who may upload, and how much ---------------------------------------------------------

    @Test
    fun `a student may not upload`() {
        assertEquals(
            403,
            api.upload("/v2/files", "file", "pixel.png", "image/png", PIXEL_PNG, Auth.asStudent(student)).status,
        )
    }

    @Test
    fun `a teacher is capped at 20 MB, and an empty file is refused`() {
        // One byte over the teacher ceiling. Admins have their own, much larger, limit; pushing a
        // gigabyte through MockMvc is not worth the minute it takes, and the branch is the same one.
        val oversized = ByteArray(20 * 1024 * 1024 + 1)
        assertEquals(
            "INVALID_PARAMETER_VALUE",
            api.upload("/v2/files", "file", "big.bin", "application/octet-stream", oversized, Auth.asTeacher(teacher))
                .errorCode,
        )
        assertEquals(
            "INVALID_PARAMETER_VALUE",
            api.upload("/v2/files", "file", "empty.bin", "application/octet-stream", ByteArray(0), Auth.asTeacher(teacher))
                .errorCode,
        )

        // And neither left a row or an object behind. The order in the controller is size check,
        // then emptiness, then store, then row — a rejected upload that had already written the
        // object would be an orphan for the sweep to find, which is survivable but is not the
        // contract.
        // The status first: `elements` answers with an empty list for a missing field, a non-JSON
        // body or an error body, so "no files listed" would otherwise be satisfied by a 403 or a
        // 500 as happily as by an empty listing.
        val listing = api.get("/v2/files/metadata", Auth.asAdmin(admin))
        assertEquals(200, listing.status) { listing.body }
        assertEquals(0, listing.elements("files").size)
        assertTrue(storageService.listKeys().isEmpty()) { "A refused upload left an object behind" }
    }

    // --- 7. delete -------------------------------------------------------------------------------

    @Test
    fun `only an admin may delete, and the file stops being served`() {
        val key = uploadedKey()

        assertEquals(403, api.delete("/v2/files/$key", Auth.asTeacher(teacher)).status)
        assertEquals(200, api.get("/v2/resource/$key/pixel.png", api.anonymous()).status) { "The 403 deleted it anyway" }

        assertEquals(200, api.delete("/v2/files/$key", Auth.asAdmin(admin)).status)
        assertEquals(404, api.get("/v2/resource/$key/pixel.png", api.anonymous()).status)
        assertEquals("ENTITY_WITH_ID_NOT_FOUND", api.delete("/v2/files/$key", Auth.asAdmin(admin)).errorCode)
    }

    /**
     * Deleting the row leaves the object, on purpose.
     *
     * `StoredFileSweep` is the only thing in this application that removes anything from storage.
     * That is what makes every partial failure collapse into "it runs again tomorrow" — and it is
     * the sort of deliberate asymmetry that reads like a leak to whoever finds it next, so it is
     * pinned here rather than left to the comment on `DeleteFileController`.
     */
    @Test
    fun `deleting a file leaves the object for the sweep`() {
        val key = uploadedKey()
        assertEquals(200, api.delete("/v2/files/$key", Auth.asAdmin(admin)).status)

        assertNull(api.get("/v2/files/metadata", Auth.asAdmin(admin)).elements("files").firstOrNull())
        assertTrue(storageService.listKeys().contains(key)) {
            "The delete endpoint removed the object. Only the sweep may do that — see StoredFileSweep."
        }
        assertNotNull(storageService.get(key))
    }

    /** A backend that has a public URL for everything, i.e. the shape of `S3StorageService`. */
    private object PublicUrlBackend : StorageService {
        override fun put(key: String, bytes: InputStream, sizeBytes: Long, mimeType: String, contentDisposition: String) =
            error("not called")

        override fun get(key: String): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun delete(keys: Collection<String>) = error("not called")
        override fun listKeys(): Set<String> = error("not called")
        override fun publicUrl(key: String) = "https://bucket.example/$key"
    }

    private companion object {
        /**
         * A real 1×1 PNG, so Tika sniffs `image/png` rather than falling back to octet-stream. The
         * same bytes `files-check.sh` wrote with a `printf`.
         */
        val PIXEL_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
