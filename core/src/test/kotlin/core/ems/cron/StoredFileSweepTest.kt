package core.ems.cron

import core.db.Account
import core.db.Article
import core.db.ArticleVersion
import core.db.CourseExercise
import core.db.StoredFile
import core.db.Submission
import core.db.TeacherActivity
import core.ems.service.storage.StorageService
import core.ems.service.storage.contentDispositionFor
import core.ems.service.storage.newStorageKey
import core.testing.Auth
import core.testing.Fixtures
import core.testing.IntegrationTest
import core.testing.TestClock
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.joda.time.DateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayInputStream

/**
 * The one thing in this application that deletes an uploaded file.
 *
 * `RichTextColumnsTest` guards the sweep's *inputs* — it fails the build when `Tables.kt` grows a
 * rich-text column that is neither scanned nor explicitly excluded. Nothing guarded what the sweep
 * then does with them, and the asymmetry matters: a missed column and a broken query have the same
 * consequence, which is **permanently deleting a file that is in use**, and neither announces
 * itself. The first sign is a broken image in an article somebody wrote a year ago.
 *
 * Four rules decide whether a file lives, and every one of them is a way to lose data if inverted:
 *
 * 1. **referenced anywhere in scanned content → keep**, and the reference is a regex match on the
 *    URL, not a foreign key
 * 2. **younger than the grace window → keep**, which covers a file uploaded into an editor whose
 *    content has not been saved yet — referenced by nothing, and still very much in use
 * 3. **marked persistent → keep**, for references the sweep cannot see at all
 * 4. **an object with no row → delete**, which is the other half of the job and the reason nothing
 *    else in the codebase is allowed to remove an object
 *
 * ### Report-only is a state worth testing
 *
 * `easy.core.stored-file-sweep.delete` ships false, so the first weeks of this job on any
 * environment are a report. A report that quietly deleted, or a delete that quietly only reported,
 * would both be discovered late — so the flag is exercised in both positions and the assertions are
 * on the *store*, not on the log line.
 */
@IntegrationTest
class StoredFileSweepTest(
    @Autowired private val sweep: StoredFileSweep,
    @Autowired private val storage: StorageService,
) {

    private val admin = Auth.ADMIN_ID
    private var articleVersionId = 0L

    /** 24 hours, matching the committed default. */
    private val grace = 24

    @BeforeEach
    fun populate() {
        TestClock.reset()
        transaction {
            Fixtures.admin(admin)
            articleVersionId = articleWithHtml("Nothing here yet")
        }
    }

    // --- 1. a referenced file survives ------------------------------------------------------------

    @Test
    fun `a file referenced from an article survives, and an unreferenced one does not`() {
        val used = storeFile(ageHours = 48)
        val unused = storeFile(ageHours = 48)

        transaction { setArticleHtml("""<p>see <img src="/v2/resource/$used/pic.png"></p>""") }

        val result = sweepNow(deleteEnabled = true)

        assertEquals(listOf(unused), result.unreferenced)
        assertTrue(rowExists(used)) { "A file referenced from an article was deleted" }
        assertFalse(rowExists(unused))
        assertTrue(storage.listKeys().contains(used))
        assertFalse(storage.listKeys().contains(unused))
    }

    @Test
    fun `the reference is found in the Markdown source as well as the rendered html`() {
        // Both are scanned even though the reference is the same in each, because they are written
        // by different code paths and nothing guarantees they agree. A file referenced only from the
        // source — the state between a save that wrote text_md and a render that has not run — must
        // not be collectable.
        val key = storeFile(ageHours = 48)
        transaction {
            ArticleVersion.update({ ArticleVersion.id eq articleVersionId }) {
                it[textHtml] = "<p>nothing</p>"
                it[textMd] = "![pic](/v2/resource/$key/pic.png)"
            }
        }

        assertTrue(sweepNow(deleteEnabled = true).unreferenced.isEmpty())
        assertTrue(rowExists(key))
    }

    /**
     * The regex is anchored on `/v2/resource/`, not on anything that looks like a key.
     *
     * Both directions are load-bearing and both are silent when wrong. A bare 27-character token in
     * a student's code sample must **not** keep a deleted file alive — that would make the sweep
     * collect nothing on any course with a base64 example in it. And the alphabet and length here
     * have to agree with `newStorageKey`; if one changes and the other does not, the sweep stops
     * finding files that are in use and starts deleting them.
     */
    @Test
    fun `a bare key-shaped string in content is not a reference`() {
        val key = storeFile(ageHours = 48)
        transaction { setArticleHtml("<pre>token = \"$key\"</pre>") }

        assertEquals(listOf(key), sweepNow(deleteEnabled = true).unreferenced)
    }

    /**
     * A reference in **teacher feedback**, which is the case that motivated the schema this sweep
     * exists to compensate for.
     *
     * Under the old design `stored_file` carried `exercise_id` and `article_id`, so a file pasted
     * into feedback had no reference at all and looked exactly like an abandoned upload. Now nothing
     * points anywhere and `SCANNED_COLUMNS` is the whole of the answer — so dropping
     * `TeacherActivity.feedbackHtml` from that list must fail a test, and until this one was
     * rewritten it did not: the test had this name and wrote into course exercise instructions.
     */
    @Test
    fun `a reference in teacher feedback keeps a file`() {
        val key = storeFile(ageHours = 48)
        transaction {
            val student = Fixtures.student("sweep-student")
            val courseId = Fixtures.course("Sweep")
            val ceId = Fixtures.courseExercise(courseId, Fixtures.exercise("Ex", admin))
            Fixtures.enrolTeacher(courseId, admin)
            Fixtures.enrolStudent(courseId, student)
            val submissionId = Fixtures.submission(ceId, student, number = 1)

            TeacherActivity.insert {
                it[courseExercise] = EntityID(ceId, CourseExercise)
                it[TeacherActivity.student] = EntityID(student, Account)
                it[submission] = EntityID(submissionId, Submission)
                it[teacher] = EntityID(admin, Account)
                it[mergeWindowStart] = TestClock.next()
                it[feedbackHtml] = """<p>see <img src="/v2/resource/$key/pic.png"></p>"""
            }
        }

        assertTrue(sweepNow(deleteEnabled = true).unreferenced.isEmpty()) {
            "A file referenced from teacher feedback was collected"
        }
    }

    @Test
    fun `a reference in course exercise instructions keeps a file`() {
        val key = storeFile(ageHours = 48)
        transaction {
            val courseId = Fixtures.course("Sweep")
            val ceId = Fixtures.courseExercise(courseId, Fixtures.exercise("Ex", admin))
            Fixtures.enrolTeacher(courseId, admin)
            CourseExercise.update({ CourseExercise.id eq ceId }) {
                it[instructionsHtml] = """<img src="/v2/resource/$key/pic.png">"""
            }
        }

        assertTrue(sweepNow(deleteEnabled = true).unreferenced.isEmpty()) {
            "A file referenced from course exercise instructions was collected"
        }
    }

    // --- 2. the grace window ----------------------------------------------------------------------

    /**
     * A file uploaded ten minutes ago is referenced by nothing, because the editor holding it has
     * not saved yet. Deleting it is deleting an image out from under whoever is still typing.
     */
    @Test
    fun `a file younger than the grace window is untouched even though nothing references it`() {
        val fresh = storeFile(ageHours = 1)
        val old = storeFile(ageHours = 48)

        assertEquals(listOf(old), sweepNow(deleteEnabled = true).unreferenced)
        assertTrue(rowExists(fresh))
    }

    @Test
    fun `the window is a strict cutoff, not a rounding`() {
        // Exactly at the boundary is inside the window and survives. Which side of the line the
        // equality falls on decides whether a file uploaded exactly one grace period ago is safe,
        // and `<` versus `<=` is one character.
        val atCutoff = storeFile(ageHours = grace)
        val justPast = storeFile(ageHours = grace + 1)

        val collected = sweepNow(deleteEnabled = true).unreferenced
        assertTrue(collected.contains(justPast))
        assertFalse(collected.contains(atCutoff)) { "A file exactly at the grace cutoff was collected" }
    }

    // --- 3. persistent ----------------------------------------------------------------------------

    @Test
    fun `a persistent file is never collected, however old and however unreferenced`() {
        val key = storeFile(ageHours = 24 * 365)
        transaction { StoredFile.update({ StoredFile.id eq key }) { it[persistent] = true } }

        assertTrue(sweepNow(deleteEnabled = true).unreferenced.isEmpty())
        assertTrue(rowExists(key))
        assertTrue(storage.listKeys().contains(key))
    }

    // --- 4. objects with no row -------------------------------------------------------------------

    @Test
    fun `an object with no row is collected`() {
        // This is what a crash between `storageService.put` and the row insert leaves behind, and
        // what `DELETE /v2/files/{id}` deliberately leaves behind. Nothing else finds it: there is
        // no row to list it from, so without this pass it is invisible junk forever.
        val orphan = newStorageKey()
        storage.put(orphan, ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, "image/png", contentDispositionFor("image/png", "x.png"))

        val result = sweepNow(deleteEnabled = true)

        assertEquals(listOf(orphan), result.orphanedObjects)
        assertFalse(storage.listKeys().contains(orphan))
    }

    @Test
    fun `an object whose row exists is not an orphan, whatever its age`() {
        // The orphan pass asks only "is there a row", with no grace window — so a file uploaded a
        // second ago is protected by its row alone. If the two passes ever disagreed about what a
        // row means, the orphan pass would delete every fresh upload.
        val fresh = storeFile(ageHours = 0)
        assertTrue(sweepNow(deleteEnabled = true).orphanedObjects.isEmpty())
        assertNotNull(storage.get(fresh))
    }

    // --- report only ------------------------------------------------------------------------------

    /**
     * The flag every environment starts with.
     *
     * Report-only must find exactly what deleting would have found — otherwise reading the report
     * and believing it, which is the entire deployment procedure for this feature, tells you nothing
     * about what flipping the flag will do.
     */
    @Test
    fun `a report-only run names the same files and deletes nothing`() {
        val unreferenced = storeFile(ageHours = 48)
        val orphan = newStorageKey()
        storage.put(orphan, ByteArrayInputStream(byteArrayOf(9)), 1, "image/png", "inline")

        val report = sweepNow(deleteEnabled = false)

        assertFalse(report.deleted)
        assertEquals(listOf(unreferenced), report.unreferenced)
        assertEquals(listOf(orphan), report.orphanedObjects)

        assertTrue(rowExists(unreferenced)) { "A report-only run deleted a row" }
        assertTrue(storage.listKeys().containsAll(listOf(unreferenced, orphan))) {
            "A report-only run deleted an object"
        }

        // And the delete run that follows collects precisely what the report named.
        val deleted = sweepNow(deleteEnabled = true)
        assertTrue(deleted.deleted)
        assertEquals(report.unreferenced, deleted.unreferenced)
        assertEquals(report.orphanedObjects, deleted.orphanedObjects)
    }

    @Test
    fun `a run with nothing to collect is not a delete`() {
        val result = sweepNow(deleteEnabled = true)
        assertTrue(result.unreferenced.isEmpty())
        assertTrue(result.orphanedObjects.isEmpty())
        // `deleted` is the point of this case: the empty-set early return reports false even with
        // the flag on, because nothing was deleted. Without this the name of the test is the only
        // thing asserting it.
        assertFalse(result.deleted)
    }

    /**
     * A storage failure must not abandon the run.
     *
     * The rows are already gone by the time objects are deleted, so a throw here would strand them —
     * and if whatever failed is permanent, which one unparseable name in a listing was, every
     * subsequent night would abort in the same place and nothing would ever be collected again.
     */
    @Test
    fun `a storage failure during deletion is swallowed and the rows still go`() {
        val key = storeFile(ageHours = 48)
        val failing = object : StorageService by storage {
            override fun delete(keys: Collection<String>) = throw RuntimeException("bucket is on fire")
        }

        val result = StoredFileSweep(failing).sweep(grace, deleteEnabled = true, runsAt)

        assertEquals(listOf(key), result.unreferenced)
        assertFalse(rowExists(key)) { "The rows were not deleted" }
        // The object survives, and is an orphan for the next run to collect — which is the design.
        assertTrue(storage.listKeys().contains(key))
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * A stored file: the object, and a row aged [ageHours] before [runsAt].
     *
     * Every instant in this class is derived from one fixed point, and the sweep is told what "now"
     * is. That makes the grace window exact rather than approximate — see [runsAt].
     */
    private fun storeFile(ageHours: Int, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): String {
        val key = newStorageKey()
        storage.put(
            key, ByteArrayInputStream(bytes), bytes.size.toLong(), "image/png",
            contentDispositionFor("image/png", "pic.png"),
        )
        transaction {
            StoredFile.insert {
                it[id] = key
                it[mimeType] = "image/png"
                it[filename] = "pic.png"
                it[sizeBytes] = bytes.size.toLong()
                it[createdAt] = runsAt.minusHours(ageHours)
                it[owner] = EntityID(admin, Account)
                it[persistent] = false
            }
        }
        return key
    }

    /**
     * The instant the sweep is told it is running at.
     *
     * A fixed point rather than the wall clock, and passed to [StoredFileSweep.sweep] explicitly.
     * Against the wall clock a row written "exactly 24 hours ago" is already a few milliseconds
     * older than that by the time the sweep computes its cutoff, so a file *at* the boundary would
     * always be collected — and the one question worth asking about a grace window would be
     * unaskable, which is how the boundary test in this class failed on its first run.
     */
    private val runsAt: DateTime = TestClock.fixed(0)

    private fun sweepNow(deleteEnabled: Boolean) = sweep.sweep(grace, deleteEnabled, runsAt)

    private fun articleWithHtml(html: String): Long {
        val newArticleId = Article.insertAndGetId {
            it[owner] = EntityID(admin, Account)
            it[published] = true
            it[createdAt] = TestClock.next()
        }
        return ArticleVersion.insertAndGetId {
            it[article] = newArticleId
            it[author] = EntityID(admin, Account)
            it[validFrom] = TestClock.next()
            it[title] = "Sweep fixture"
            it[textHtml] = html
        }.value
    }

    private fun setArticleHtml(html: String) =
        ArticleVersion.update({ ArticleVersion.id eq articleVersionId }) { it[textHtml] = html }

    private fun rowExists(key: String) = transaction {
        StoredFile.select(StoredFile.id).where { StoredFile.id eq key }.count() > 0
    }
}
