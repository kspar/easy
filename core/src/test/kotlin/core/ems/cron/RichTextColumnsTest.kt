package core.ems.cron

import core.db.Account
import core.db.Asset
import core.db.AnonymousSubmission
import core.db.AutoExercise
import core.db.AutogradeActivity
import core.db.ContainerImage
import core.db.Course
import core.db.CourseExercise
import core.db.CourseGroup
import core.db.CourseInviteLink
import core.db.Dir
import core.db.Exercise
import core.db.ExerciseVer
import core.db.Executor
import core.db.Group
import core.db.LogReport
import core.db.ManagementNotification
import core.db.StatsSubmission
import core.db.StoredFile
import core.db.StudentCourseAccess
import core.db.StudentMoodlePendingAccess
import core.db.StudentMoodlePendingCourseGroup
import core.db.Submission
import core.db.SubmissionDraft
import core.db.SystemConfiguration
import core.db.TeacherInlineComment
import core.db.TeacherSubmission
import core.db.ArticleAlias
import core.db.ArticleVersion
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter

/**
 * Guards the one thing [StoredFileSweep] cannot survive being wrong about: the list of columns a
 * file reference can appear in.
 *
 * `stored_file` records no reference to what a file is attached to, so the sweep answers "still in
 * use?" by looking for the key inside content. A rich-text column that nobody adds to
 * [SCANNED_COLUMNS] therefore does not merely go unswept — files referenced *only* from it look
 * unreferenced, and the sweep deletes them. Adding a column to `Tables.kt` is a normal, frequent
 * thing to do; noticing that it changed the correctness of a cron job in another package is not.
 *
 * So: every `text` column in `core.db` must be named here, either as scanned or as deliberately
 * excluded with a reason. Adding one and running the tests fails until someone has decided which it
 * is.
 *
 * **Context-free on purpose.** The obvious implementation reads `information_schema`, which needs a
 * database, which means `@Tag("db")`, which CI excludes — a guard that never runs. Reflecting over
 * the Exposed table objects checks the same thing against the definitions the application actually
 * uses, and runs everywhere.
 */
class RichTextColumnsTest {

    /**
     * Text columns the sweep deliberately does not scan, each with the reason.
     *
     * The bar for adding something here: could a person put an image reference in it? Names,
     * titles, colours and identifiers cannot hold one usefully — nothing renders them as Markdown or
     * HTML, so a `/v2/resource/...` string in a course title is text, not a reference. Code and
     * solution columns are user-controlled but are shown verbatim in an editor, never rendered.
     */
    private val excluded: Map<Column<*>, String> = mapOf(
        // Identity and contact details.
        Account.id to "username",
        Account.email to "e-mail address",
        Account.givenName to "personal name",
        Account.familyName to "personal name",
        Account.preMigrationId to "legacy identifier",
        Account.pseudonym to "generated identifier",
        StatsSubmission.studentPseudonym to "generated identifier",
        StatsSubmission.latestTeacherPseudonym to "generated identifier",
        StudentCourseAccess.moodleUsername to "external username",
        StudentMoodlePendingAccess.moodleUsername to "external username",
        StudentMoodlePendingAccess.email to "e-mail address",
        StudentMoodlePendingCourseGroup.moodleUsername to "external username",
        StudentMoodlePendingAccess.inviteId to "generated identifier",
        CourseInviteLink.inviteId to "generated identifier",

        // Names, titles, labels and identifiers. Rendered as plain text everywhere.
        Course.title to "plain title",
        Course.alias to "plain identifier",
        Course.moodleShortName to "external identifier",
        Course.color to "colour value",
        Course.courseCode to "external identifier",
        CourseGroup.name to "plain name",
        Group.name to "plain name",
        Group.color to "colour value",
        Dir.name to "plain name",
        ExerciseVer.title to "plain title",
        ExerciseVer.aasId to "external identifier",
        ExerciseVer.solutionFileName to "plain filename",
        ArticleVersion.title to "plain title",
        ArticleAlias.id to "plain identifier",
        CourseExercise.titleAlias to "plain title",
        CourseExercise.moodleExId to "external identifier",
        ManagementNotification.message to "plain text; only link_url can hold a URL, and that is scanned",
        ManagementNotification.severity to "enum stored as text",
        ManagementNotification.linkLabel to "plain label",
        ContainerImage.id to "plain identifier",

        // Student and teacher code, and grader output. User-controlled, but shown in a code editor
        // or as preformatted text — never rendered as Markdown, so a URL in one is not a reference.
        Submission.solution to "solution code, never rendered",
        SubmissionDraft.solution to "solution code, never rendered",
        AnonymousSubmission.solution to "solution code, never rendered",
        AnonymousSubmission.feedback to "grader output, never rendered",
        TeacherSubmission.solution to "solution code, never rendered",
        TeacherSubmission.feedback to "grader output, never rendered",
        AutogradeActivity.feedback to "grader output, never rendered",
        TeacherInlineComment.code to "solution code, never rendered",
        TeacherInlineComment.suggestedCode to "solution code, never rendered",
        TeacherInlineComment.type to "enum stored as text",
        Exercise.anonymousAutoassessTemplate to "grading template, never rendered",
        AutoExercise.gradingScript to "grading script, never rendered",
        Asset.fileName to "grading asset name",
        Asset.fileContent to "grading asset content, never rendered",

        // Infrastructure and diagnostics.
        Executor.name to "plain name",
        Executor.baseUrl to "executor address set by an admin, not content",
        SystemConfiguration.id to "configuration key",
        SystemConfiguration.value to "configuration value set by an admin, not content",
        LogReport.logMessage to "client diagnostics",
        LogReport.logLevel to "enum stored as text",
        LogReport.clientId to "generated identifier",

        // The sweep's own table. A file cannot reference a file.
        StoredFile.id to "the storage key itself",
        StoredFile.mimeType to "sniffed MIME type",
        StoredFile.filename to "plain filename",
    )

    private fun allTextColumns(): List<Pair<Table, Column<*>>> {
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            // The default asks for a concrete, independent, @Component-annotated candidate. These
            // are plain Kotlin objects, so without this every table is filtered out and the test
            // passes by finding nothing — the worst possible failure for a guard.
            override fun isCandidateComponent(
                beanDefinition: org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
            ) = true
        }
        scanner.addIncludeFilter(AssignableTypeFilter(Table::class.java))

        return scanner.findCandidateComponents("core.db")
            .mapNotNull { Class.forName(it.beanClassName).kotlin.objectInstance as? Table }
            .flatMap { table -> table.columns.map { table to it } }
            .filter { (_, column) -> column.columnType is TextColumnType }
    }

    @Test
    fun `every text column is either scanned by the sweep or explicitly excluded`() {
        val unaccounted = allTextColumns()
            .filter { (_, column) -> column !in SCANNED_COLUMNS && column !in excluded }
            .map { (table, column) -> "${table.tableName}.${column.name}" }
            .sorted()

        assertTrue(unaccounted.isEmpty()) {
            "These text columns are neither scanned by StoredFileSweep nor listed as excluded:\n" +
                    unaccounted.joinToString("\n") { "  $it" } +
                    "\n\nIf a person can paste an image into one, add it to SCANNED_COLUMNS in " +
                    "stored_file_sweep.kt — otherwise files referenced only from it will be deleted. " +
                    "If not, add it to the excluded map in this test with the reason."
        }
    }

    @Test
    fun `the scan reaches every table that holds rendered content`() {
        // Named tables rather than a count, so that renaming or splitting one is a visible failure
        // rather than a number that still adds up.
        assertEquals(
            listOf(
                "article_version", "course_exercise", "exercise_version",
                "feedback_snippet", "management_notification", "teacher_activity",
                "teacher_inline_comment",
            ),
            SCANNED_COLUMNS.map { it.table.tableName }.distinct().sorted(),
        )
    }

    @Test
    fun `reflection actually found the tables`() {
        // If the scanner silently returns nothing, both tests above pass while checking nothing.
        val found = allTextColumns()
        assertTrue(found.size > 50) { "Only found ${found.size} text columns; the classpath scan is broken" }
    }

    @Test
    fun `the reference regex matches what newStorageKey produces`() {
        val key = core.ems.service.storage.newStorageKey()
        val found = STORED_FILE_URL_REGEX.findAll("""<img src="/v2/resource/$key/diagram.png">""")
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf(key), found)
    }

    @Test
    fun `a bare key with no resource path is not treated as a reference`() {
        // Otherwise a base64-looking token in someone's code sample would keep a deleted file alive.
        val key = core.ems.service.storage.newStorageKey()
        assertTrue(STORED_FILE_URL_REGEX.findAll("here is a token: $key").none())
    }
}
