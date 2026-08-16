package core.ems.cron

import core.db.ArticleVersion
import core.db.CourseExercise
import core.db.ExerciseVer
import core.db.FeedbackSnippet
import core.db.ManagementNotification
import core.db.StoredFile
import core.db.TeacherActivity
import core.db.TeacherInlineComment
import core.ems.service.storage.STORAGE_KEY_LENGTH
import core.ems.service.storage.StorageService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The one thing in this application that decides an uploaded file is garbage.
 *
 * `stored_file` records no reference to whatever a file is attached to, on purpose — see the table's
 * KDoc. So "is this file still in use?" is answered the only way it can be: by looking for its key in
 * every column a person could have pasted it into.
 *
 * **The risk this design moves rather than removes.** Before, a file that nothing referenced simply
 * accumulated forever; the failure was junk. Now a column missing from [SCANNED_COLUMNS] means
 * permanently deleting a file that is in use, which is strictly worse. Two things guard it:
 * `RichTextColumnsTest`, which fails the build when `Tables.kt` grows a text column that is neither
 * scanned nor explicitly excluded, and `easy.core.stored-file-sweep.delete`, which starts false so
 * that the first weeks of this job are a report rather than a deletion.
 *
 * **One deleter.** Nothing else removes an object from storage — not the upload path, not
 * `DELETE /v2/files/{id}`, not deleting an exercise or an article. Those delete rows and leave the
 * object to this job, which also collects objects with no row at all. Every partial failure then
 * collapses into "it runs again tomorrow" instead of leaving something unreachable that nobody will
 * ever find.
 */
@Component
class StoredFileSweep(private val storageService: StorageService) {
    private val log = KotlinLogging.logger {}

    @Value("\${easy.core.stored-file-sweep.grace-hours}")
    private var graceHours: Int = 24

    /**
     * False until the logged candidates have been read and believed. Flipping it is the whole
     * deployment risk of this feature, so it is a deliberate second step rather than a default.
     */
    @Value("\${easy.core.stored-file-sweep.delete}")
    private var deleteEnabled: Boolean = false

    /**
     * What one run decided, so that a caller can assert on it rather than on a log line.
     *
     * [deleted] is false on a report-only run, which is the state this job ships in and the state
     * every environment starts in — so it is the difference between "these would have gone" and
     * "these are gone", and the two must never be confused by whoever reads the output.
     */
    data class Result(
        val unreferenced: List<String>,
        val orphanedObjects: List<String>,
        val bytes: Long,
        val deleted: Boolean,
    )

    /**
     * The nightly entry point: reads configuration, and discards the result because nobody is
     * listening. Void deliberately — a `@Scheduled` method's return value goes nowhere, so returning
     * one would invite a caller to believe otherwise.
     */
    @Scheduled(cron = "\${easy.core.stored-file-sweep.cron}")
    fun cron() {
        sweep(graceHours, deleteEnabled)
    }

    /**
     * One sweep, with its inputs passed in rather than read from configuration and the clock.
     *
     * Extracted from [cron] so that all three are reachable from a test without a second Spring
     * context: the grace window and the delete flag are the two things `@Value` makes fixed for the
     * life of the process, and [now] is the third. `cron` is the part that reads configuration; this
     * is the part that does the work.
     *
     * [now] exists because the boundary is otherwise unobservable. The cutoff is *relative to the
     * moment of the run*, so against the wall clock a fixture written "exactly 24 hours ago" is
     * already fractionally older than that by the time the sweep computes its cutoff — and the one
     * question worth asking about a grace window, whether a file *at* the boundary is safe, cannot
     * be put. Defaulted, so no caller outside a test knows this parameter exists.
     */
    fun sweep(graceHours: Int, deleteEnabled: Boolean, now: DateTime = DateTime.now()): Result {
        val unreferenced = transaction {
            // Every key mentioned anywhere in content. Built by scanning the corpus once and pulling
            // keys out with a regex — NOT by asking, per file, whether any row contains it. The
            // latter is a files × rows substring scan; this is one pass over roughly 20 MB.
            val referenced = SCANNED_COLUMNS.flatMapTo(mutableSetOf()) { referencedKeysIn(it) }

            // The grace window covers a file uploaded into an editor whose content has not been
            // saved yet: it is referenced by nothing anywhere, and without this it would be deleted
            // out from under whoever is still typing.
            val cutoff = now.minusHours(graceHours)

            StoredFile
                .select(StoredFile.id, StoredFile.filename, StoredFile.sizeBytes)
                .where { (StoredFile.createdAt less cutoff) and (StoredFile.persistent eq false) }
                .map { Triple(it[StoredFile.id].value, it[StoredFile.filename], it[StoredFile.sizeBytes]) }
                .filter { (key, _, _) -> key !in referenced }
        }

        val orphanedObjects = findOrphanedObjects()

        val bytes = unreferenced.sumOf { it.third }
        val keys = unreferenced.map { it.first }

        if (unreferenced.isEmpty() && orphanedObjects.isEmpty()) {
            log.info { "Stored file sweep: nothing to collect" }
            return Result(keys, orphanedObjects, bytes, deleted = false)
        }

        if (!deleteEnabled) {
            log.info {
                "Stored file sweep (REPORT ONLY, easy.core.stored-file-sweep.delete is false): " +
                        "would delete ${unreferenced.size} unreferenced file(s) totalling $bytes bytes " +
                        "${unreferenced.map { "${it.first} (${it.second})" }}, and " +
                        "${orphanedObjects.size} object(s) with no row $orphanedObjects"
            }
            return Result(keys, orphanedObjects, bytes, deleted = false)
        }

        // Rows first. A row with no object is a broken image someone notices; an object with no row
        // is invisible — but it is also exactly what the orphan pass above collects, so this
        // ordering makes a half-finished run self-repairing on the next one.
        transaction { StoredFile.deleteWhere { StoredFile.id inList keys } }

        // Never let a storage failure escape. The rows are already gone at this point, so throwing
        // here would abort the run with the objects stranded — and if whatever failed is permanent
        // (one unparseable name in the listing was the first version's bug) every subsequent night
        // would abort in the same place and nothing would ever be collected again.
        try {
            storageService.delete(keys + orphanedObjects)
        } catch (e: Exception) {
            log.error(e) { "Could not delete stored objects; they are orphaned and go on the next run" }
        }

        log.info {
            "Stored file sweep: deleted ${keys.size} unreferenced file(s) totalling $bytes bytes " +
                    "$keys, and ${orphanedObjects.size} object(s) with no row $orphanedObjects"
        }
        return Result(keys, orphanedObjects, bytes, deleted = true)
    }

    /**
     * Objects in storage that no row points at — the other half of the job, and the reason nothing
     * else is allowed to delete an object. A failed listing skips this pass rather than abandoning
     * the run: the reference scan above is the half that matters.
     */
    private fun findOrphanedObjects(): List<String> = try {
        val known = transaction { StoredFile.selectAll().map { it[StoredFile.id].value }.toSet() }
        storageService.listKeys().filter { it !in known }
    } catch (e: Exception) {
        log.error(e) { "Could not list stored objects; skipping the orphaned-object pass" }
        emptyList()
    }

    // Column<*> rather than Column<String?>: some of these are nullable and some are not, which in
    // Exposed are different types but the same job here.
    private fun referencedKeysIn(column: Column<*>): Set<String> = buildSet {
        column.table
            .select(column)
            .forEach { row ->
                (row[column] as String?)?.let { text ->
                    STORED_FILE_URL_REGEX.findAll(text).forEach { add(it.groupValues[1]) }
                }
            }
    }
}

/**
 * How a file appears inside content: `/v2/resource/<key>/<filename>`.
 *
 * The alphabet and length have to agree with `newStorageKey`, which produces 27 base64url
 * characters. If one changes and the other does not, this stops finding keys that are in use and
 * the sweep starts deleting live files — which is the single most expensive way to get this wrong.
 *
 * Anchored on the `/v2/resource/` prefix rather than matching bare 27-character tokens, so a random
 * base64-looking string in someone's code sample cannot accidentally keep a deleted file alive.
 */
val STORED_FILE_URL_REGEX = Regex("/v2/resource/([A-Za-z0-9_-]{$STORAGE_KEY_LENGTH})/")

/**
 * Every column a file reference can appear in.
 *
 * **This list is the correctness of the sweep.** Adding a rich-text field anywhere without adding it
 * here means files referenced only from that field are silently deleted — `RichTextColumnsTest`
 * exists to make that impossible to do by accident.
 *
 * Both the rendered `_html` and the `_md` source are scanned even though the reference is the same
 * in each, because they are edited independently by different code paths and there is no invariant
 * saying they agree. The `_adoc` columns are the sharp ones: they are read-only leftovers from before
 * EZ-1729, they carry `image::<id>[]` macros, and they are the only surviving copy of the source for
 * anything authored in AsciiDoc.
 *
 * `management_notification.link_url` is not rich text at all — it is a plain URL an admin sets on a
 * system message. It is scanned because doing so costs nothing and removes one of the few reasons a
 * file would otherwise need marking persistent.
 */
val SCANNED_COLUMNS: List<Column<*>> = listOf(
    ExerciseVer.textHtml, ExerciseVer.textMd, ExerciseVer.textAdoc,
    ArticleVersion.textHtml, ArticleVersion.textMd, ArticleVersion.textAdoc,
    CourseExercise.instructionsHtml, CourseExercise.instructionsMd, CourseExercise.instructionsAdoc,
    TeacherActivity.feedbackHtml, TeacherActivity.feedbackMd,
    TeacherInlineComment.textHtml, TeacherInlineComment.textMd,
    FeedbackSnippet.snippetHtml, FeedbackSnippet.snippetMd,
    ManagementNotification.linkUrl,
)
