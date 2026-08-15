package core.db


import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jodatime.datetime
import org.jetbrains.exposed.v1.json.jsonb
import org.joda.time.DateTime


object Account : IdTable<String>("account") {
    override val id = text("username").entityId()
    override val primaryKey = PrimaryKey(id)
    val createdAt = datetime("created_at")
    val lastSeen = datetime("last_seen")
    val email = text("email")
    val givenName = text("given_name")
    val familyName = text("family_name")
    val idMigrationDone = bool("id_migration_done")
    val preMigrationId = text("pre_migration_id").nullable()
    val isTeacher = bool("is_teacher")
    val isStudent = bool("is_student")
    val isAdmin = bool("is_admin")
    val pseudonym = text("pseudonym")
}


object Exercise : LongIdTable("exercise") {
    // NOT NULL in the schema since changeset 100821-1 in v2.xml, which backfilled every exercise's
    // implicit dir and then added the constraint — its comment says "it cannot be null". This
    // declaration kept `.nullable()` for four years anyway, so every read handed out a `Long?` that
    // could never be null and every write could type-check a null the database would reject.
    // Found by SchemaMatchesTablesTest.
    val dir = reference("dir_id", Dir)
    val owner = reference("owned_by_id", Account)
    val createdAt = datetime("created_at")
    val public = bool("public")
    val anonymousAutoassessEnabled = bool("anonymous_autoassess_enabled")
    // Empty string, not null, when there is no template — see changeset 020826-1. Nullable meant
    // "no template" had two spellings and the PATCH that writes it could never restore the first.
    val anonymousAutoassessTemplate = text("anonymous_autoassess_template").default("")
}

/**
 * Versions of an exercise's content. The current one is the row with a null [ExerciseVer.validTo],
 * and that is how every read in core finds it — none of them ask which version was valid at a given
 * instant, which is what lets the ranges be what they are. See the note on [ExerciseVer.validFrom].
 */
object ExerciseVer : LongIdTable("exercise_version") {
    val exercise = reference("exercise_id", Exercise)
    val author = reference("author_id", Account)
    val previous = reference("previous_id", ExerciseVer).nullable()
    val autoExerciseId = reference("auto_exercise_id", AutoExercise).nullable()

    /**
     * **These ranges are not guaranteed to be mutually exclusive, and never were meant to be read
     * as a history of what was live when.**
     *
     * An ordinary save closes the old row at `now` and opens the new one at `now`, so the two meet
     * exactly. `PUT /v2/admin/exercises/{id}/rewrite` (UpdateExercise.rewriteController) instead
     * gives the new row the old one's `valid_from` plus a millisecond, so that a mechanical
     * migration does not look like an edit — which means the superseded row's `valid_to` sits
     * *after* its successor's `valid_from`, and the two intervals overlap by however long the old
     * version was actually current.
     *
     * Ordering is therefore always by `valid_from`, and the millisecond is what keeps that total.
     *
     * **Finding the rewrites, if it is ever necessary:** they are exactly the links where the
     * superseded row's `valid_to` is strictly greater than its successor's `valid_from`. For an
     * ordinary save the two are equal. That comparison is the only record that a rewrite happened —
     * it leaves `author` and `valid_from` deliberately untouched — so `valid_to` is kept honest
     * (the real wall-clock instant of the write) precisely to preserve it.
     */
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val graderType = enumerationByName("grader_type", 20, GraderType::class)
    val aasId = text("aas_id").nullable()
    val title = text("title")
    val textHtml = text("text_html").nullable()
    val textMd = text("text_md").nullable()

    // Source format before EZ-1729. Read-only: still the only copy of the source for anything
    // authored in adoc, but nothing writes it and nothing renders it any more.
    val textAdoc = text("text_adoc").nullable()
    val solutionFileName = text("solution_file_name")
    val solutionFileType = enumeration("solution_file_type", SolutionFileType::class)
}

object Course : LongIdTable("course") {
    val createdAt = datetime("created_at")
    val title = text("title")
    val alias = text("alias").nullable()
    val moodleShortName = text("moodle_short_name").nullable()
    val moodleSyncStudents = bool("moodle_sync_students")
    val moodleSyncStudentsInProgress = bool("moodle_sync_students_in_progress")
    val moodleSyncGrades = bool("moodle_sync_grades")
    val moodleSyncGradesInProgress = bool("moodle_sync_grades_in_progress")
    val archived = bool("archived")
    val lastSubmissionAt = datetime("last_submission_at").nullable()
    val color = text("color")
    val courseCode = text("course_code").nullable()
}

object CourseGroup : LongIdTable("course_group") {
    val name = text("name")
    val course = reference("course_id", Course)
}

object CourseInviteLink : Table("course_invite_link") {
    val inviteId = text("invite_id")
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val course = reference("course_id", Course)
    val allowedUses = integer("allowed_uses")
    val usedCount = integer("used_count")
    override val primaryKey = PrimaryKey(course)
}

object CourseExercise : LongIdTable("course_exercise") {
    val course = reference("course_id", Course)
    val exercise = reference("exercise_id", Exercise)
    val createdAt = datetime("created_at")
    val modifiedAt = datetime("modified_at")
    val gradeThreshold = integer("grade_threshold")

    // if null then permanently invisible
    // if in past or now then visible, if in future then invisible
    val studentVisibleFrom = datetime("student_visible_from").nullable()
    val softDeadline = datetime("soft_deadline").nullable()
    val hardDeadline = datetime("hard_deadline").nullable()
    val orderIdx = integer("ordering_index")
    val assessmentsStudentVisible = bool("assessments_student_visible")
    val instructionsHtml = text("instructions_html").nullable()
    val instructionsMd = text("instructions_md").nullable()

    // See the note on ExerciseVer.textAdoc — read-only since EZ-1729.
    val instructionsAdoc = text("instructions_adoc").nullable()
    val titleAlias = text("title_alias").nullable()
    val moodleExId = text("moodle_exercise_id").nullable()
}

abstract class CourseExerciseException(name: String) : Table(name) {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val isExceptionSoftDeadline = bool("is_exception_soft_deadline")
    val softDeadline = datetime("soft_deadline").nullable()
    val isExceptionHardDeadline = bool("is_exception_hard_deadline")
    val hardDeadline = datetime("hard_deadline").nullable()
    val isExceptionStudentVisibleFrom = bool("is_exception_student_visible_from")
    val studentVisibleFrom = datetime("student_visible_from").nullable()
    override val primaryKey = PrimaryKey(courseExercise)
}

object CourseExerciseExceptionStudent : CourseExerciseException("course_exercise_exception_student") {
    val student = reference("student_id", Account)
}

object CourseExerciseExceptionGroup : CourseExerciseException("course_exercise_exception_group") {
    val courseGroup = reference("group_id", CourseGroup)
}


object TeacherCourseAccess : Table("teacher_course_access") {
    val teacher = reference("teacher_id", Account)
    val course = reference("course_id", Course)
    val createdAt = datetime("created_at")
    val lastAccessed = datetime("last_accessed").clientDefault { DateTime.now() }
    override val primaryKey = PrimaryKey(teacher, course)
}

object StudentCourseAccess : Table("student_course_access") {
    val student = reference("student_id", Account)
    val course = reference("course_id", Course)
    val createdAt = datetime("created_at")
    val moodleUsername = text("moodle_username").nullable()
    val lastAccessed = datetime("last_accessed").clientDefault { DateTime.now() }
    override val primaryKey = PrimaryKey(student, course)
}

object StudentCourseGroup : Table("student_course_group_access") {
    val student = reference("student_id", Account)
    val course = reference("course_id", Course)
    val courseGroup = reference("group_id", CourseGroup)
    override val primaryKey = PrimaryKey(student, course, courseGroup)
}

object StudentMoodlePendingAccess : Table("student_moodle_pending_access") {
    val course = reference("course_id", Course)
    val moodleUsername = text("moodle_username")
    val email = text("email")
    val createdAt = datetime("created_at")
    val inviteId = text("invite_id")
    override val primaryKey = PrimaryKey(course, moodleUsername)
}

object StudentMoodlePendingCourseGroup : Table("student_moodle_pending_course_group_access") {
    val moodleUsername = reference("moodle_username", StudentMoodlePendingAccess.moodleUsername)
    val course = reference("course_id", Course)
    val courseGroup = reference("group_id", CourseGroup)
    override val primaryKey = PrimaryKey(moodleUsername, course, courseGroup)
}

object Submission : LongIdTable("submission") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_id", Account)
    val createdAt = datetime("created_at")
    val solution = text("solution")
    val grade = integer("grade").nullable()
    val isAutoGrade = bool("is_auto_grade").nullable()
    val autoGradeStatus = enumerationByName("auto_grade_status", 20, AutoGradeStatus::class)
    val seen = bool("seen")
    val number = integer("number")
    val isGradedDirectly = bool("is_graded_directly").nullable()
}

object StatsSubmission : Table("stats_submission") {
    val submissionId = long("submission_id")
    val courseExerciseId = long("course_exercise_id")
    val exerciseId = long("exercise_id")
    val studentPseudonym = text("student_pseudonym")
    val latestTeacherPseudonym = text("latest_teacher_pseudonym").nullable()
    val createdAt = datetime("created_at")
    val solutionLength = integer("solution_length")
    val teacherPoints = integer("teacher_points").nullable()
    val hasEverReceivedTeacherComment = bool("has_ever_received_teacher_comment")
    val latestTeacherActivityUpdate = datetime("latest_teacher_activity_update").nullable()
    val autoPoints = integer("auto_points").nullable()
    val autoGradedAt = datetime("auto_graded_at").nullable()
    override val primaryKey = PrimaryKey(submissionId)
}

object TeacherActivity : LongIdTable("teacher_activity") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_id", Account)
    val submission = reference("submission_id", Submission)
    val teacher = reference("teacher_id", Account)
    val mergeWindowStart = datetime("merge_window_start")
    val editedAt = datetime("edited_at").nullable()
    val grade = integer("grade").nullable()
    val feedbackMd = text("feedback_md").nullable()
    val feedbackHtml = text("feedback_html").nullable()
}

object TeacherInlineComment : LongIdTable("teacher_inline_comment") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val submission = reference("submission_id", Submission)
    val teacher = reference("teacher_id", Account)
    val createdAt = datetime("created_at")
    val editedAt = datetime("edited_at").nullable()
    val lineStart = integer("line_start")
    val lineEnd = integer("line_end")
    val code = text("code")
    val textMd = text("text_md")
    val textHtml = text("text_html")
    val type = text("type")
    val suggestedCode = text("suggested_code").nullable()
}

object AutogradeActivity : LongIdTable("autograde_activity") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_id", Account)
    val submission = reference("submission_id", Submission)
    val createdAt = datetime("created_at")
    val grade = integer("grade")
    val feedback = text("feedback").nullable()
}

object AnonymousSubmission : LongIdTable("anonymous_submission") {
    val exercise = reference("exercise_id", Exercise)
    val createdAt = datetime("created_at")
    val solution = text("solution")
    val grade = integer("grade")
    val feedback = text("feedback").nullable()
}

object StatsAnonymousSubmission : LongIdTable("stats_anonymous_submission") {
    val exercise = long("exercise_id")
    val createdAt = datetime("created_at")
    val solutionLength = integer("solution_length")
    val points = integer("points")
}

object ManagementNotification : LongIdTable("management_notification") {
    val message = text("message")

    // URGENT is sticky and cannot be dismissed; INFO is quiet and can be. Stored as text rather
    // than a DB enum for the same reason every other enum here is: adding a value should be a code
    // change, not a migration.
    val severity = text("severity")
    val linkUrl = text("link_url").nullable()
    val linkLabel = text("link_label").nullable()

    // Null is "no bound", not "unset": null from means visible immediately, null until means
    // visible until someone removes it. That is what lets the rows written before this existed
    // keep behaving as they did without a backfill.
    val visibleFrom = datetime("visible_from").nullable()
    val visibleUntil = datetime("visible_until").nullable()

    // Three booleans rather than a join table. There are exactly three roles and the set does not
    // change; a join table would be tidier in the abstract and worse at every query that runs.
    val forStudents = bool("for_students")
    val forTeachers = bool("for_teachers")
    val forAdmins = bool("for_admins")
}

object AutoExercise : LongIdTable("automatic_exercise") {
    val gradingScript = text("grading_script")
    val containerImage = reference("container_image_id", ContainerImage)
    val maxTime = integer("max_time_sec")
    val maxMem = integer("max_mem_mb")
}

object Asset : LongIdTable("asset") {
    val autoExercise = reference("auto_exercise_id", AutoExercise)
    val fileName = text("file_name")
    val fileContent = text("file_content")
}

object Executor : LongIdTable("executor") {
    val name = text("name")
    val baseUrl = text("base_url")
    val maxLoad = integer("max_load")
    val drain = bool("drain")
}

object ContainerImage : IdTable<String>("container_image") {
    override val id: Column<EntityID<String>> = text("id").entityId()
    override val primaryKey = PrimaryKey(id)
}

object ExecutorContainerImage : Table("executor_container_image") {
    val executor = reference("executor_id", Executor)
    val containerImage = reference("container_image_id", ContainerImage)
}

object SubmissionDraft : Table("submission_draft") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_id", Account)
    val createdAt = datetime("created_at")
    val solution = text("solution")
    override val primaryKey = PrimaryKey(courseExercise, student)
}

object TeacherSubmission : LongIdTable("teacher_submission") {
    val teacher = reference("teacher_id", Account)
    val exercise = reference("exercise_id", Exercise)
    val createdAt = datetime("created_at")
    val solution = text("solution")

    // The result of testing this solution. Nullable because the row is written before the grading
    // it describes: a submission whose executor never answered keeps the solution and has no
    // result, which is what lets a teacher retry it instead of retyping it. See changeset 120826-1.
    val grade = integer("grade").nullable()
    val feedback = text("feedback").nullable()
}

object FeedbackSnippet : LongIdTable("feedback_snippet") {
    val teacher = reference("teacher_id", Account)
    val createdAt = datetime("created_at")
    val snippetHtml = text("snippet_html")
    val snippetMd = text("snippet_md")
}

object SystemConfiguration : IdTable<String>("system_configuration") {
    override val id: Column<EntityID<String>> = text("key").entityId()
    override val primaryKey = PrimaryKey(id)
    val value = text("value").nullable()
}

object LogReport : LongIdTable("log_report") {
    val userId = reference("user_id", Account)
    val logTime = datetime("log_time")
    val logLevel = text("log_level")
    val logMessage = text("log_message")
    val clientId = text("client_id")
}

object Article : LongIdTable("article") {
    val owner = reference("owner_id", Account)
    val createdAt = datetime("created_at")

    // Whether anyone may read it, including someone with no account. False is a draft: visible to
    // admins only. Enforced in CachingService.selectLatestArticleVersion — before changeset
    // 130826-1 this was called `public` and nothing read it at all.
    val published = bool("published")
}

object ArticleVersion : LongIdTable("article_version") {
    val article = reference("article_id", Article)
    val previous = reference("previous_id", ArticleVersion).nullable()
    val author = reference("author_id", Account)
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val title = text("title")
    val textHtml = text("text_html").nullable()
    val textMd = text("text_md").nullable()

    // See the note on ExerciseVer.textAdoc — read-only since EZ-1729.
    val textAdoc = text("text_adoc").nullable()
}

object ArticleAlias : IdTable<String>("article_alias") {
    override val id: Column<EntityID<String>> = text("alias").entityId()
    override val primaryKey = PrimaryKey(id)
    val article = reference("article_id", Article)
    val createdAt = datetime("created_at")
    val owner = reference("created_by_id", Account)
}

/**
 * Metadata for an uploaded file. **The bytes are not here** — they live in object storage under
 * [id], which is simultaneously the row id, the storage key and the last-but-one segment of the
 * URL the file is served from (`/v2/resource/<id>/<filename>`). One identifier, three uses.
 *
 * **There is deliberately no reference to whatever the file is attached to.** A file reference can
 * appear in sixteen columns across seven tables, and a foreign key can only ever model the ones
 * somebody thought of — which is how it was before: this table had `exercise_id` and `article_id`,
 * so a file pasted into teacher feedback had no reference at all and looked exactly like an
 * abandoned upload. Instead nothing points anywhere, and one nightly sweep
 * (`core.ems.cron.StoredFileSweep`) decides what is garbage by looking for the key in every column
 * it could appear in. The cost is that deletion is not immediate: delete an exercise and its images
 * survive until the sweep runs.
 */
object StoredFile : IdTable<String>("stored_file") {
    /**
     * 160 CSPRNG bits, base64url, 27 characters — `core.ems.service.storage.newStorageKey`.
     *
     * Unguessable by design and not by accident: stored objects are publicly readable and the read
     * endpoint checks no permissions, so this id *is* the credential for the file it names.
     */
    override val id: Column<EntityID<String>> = text("id").entityId()
    override val primaryKey = PrimaryKey(id)
    val mimeType = text("mime_type")
    val sizeBytes = long("size_bytes")
    val filename = text("filename")
    val createdAt = datetime("created_at")
    val owner = reference("created_by_id", Account)

    /**
     * **"Referenced somewhere the sweep cannot check" — not "important".**
     *
     * The sweep finds references by looking for the id inside our own content columns. Some files
     * are referenced by things it cannot see: a PDF linked from an e-mail, a slide or a syllabus, a
     * branding asset named in configuration rather than content. Marking those persistent is the
     * only way they survive; the same argument as article aliases, where a URL someone wrote down
     * elsewhere is a real reference we have no record of.
     *
     * The misreading to guard against is "do not lose this", which ends with everything marked
     * persistent and nothing ever reaped. A persistent file is **never** cleaned up, by definition,
     * which is why it is admin-only, expected to be rare, and listed by
     * `GET /v2/files/metadata?persistent=true` so that it is auditable rather than invisible.
     *
     * A file referenced from a column the sweep *could* scan does not want this flag — the honest
     * fix there is to add the column to the scan list.
     */
    val persistent = bool("persistent")
}

object Group : LongIdTable("group") {
    val name = text("name")
    val color = text("color").nullable()
    val isImplicit = bool("implicit").clientDefault { false }
    val createdAt = datetime("created_at")
}

object AccountGroup : Table("account_group_access") {
    val account = reference("account_id", Account)
    val group = reference("group_id", Group)
    val isManager = bool("manager")
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(account, group)
}

object Dir : LongIdTable("exercise_dir") {
    val name = text("name")
    val isImplicit = bool("implicit").clientDefault { false }
    val parentDir = reference("parent", Dir).nullable()

    // Access level given to any account for this dir,
    // i.e. anyAccess == R would give all accounts read access without any other explicit permissions
    val anyAccess = enumeration("any_account_access_level", DirAccessLevel::class).nullable()
    val createdAt = datetime("created_at")
    val modifiedAt = datetime("modified_at")
}

object GroupDirAccess : Table("group_exercise_dir_access") {
    val group = reference("group_id", Group)
    val dir = reference("dir_id", Dir)
    val level = enumeration("access_level", DirAccessLevel::class)
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(group, dir)
}