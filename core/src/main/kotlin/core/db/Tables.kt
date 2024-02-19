package core.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.jodatime.datetime


object Account : IdTable<String>("account") {
    override val id = text("username").entityId()
    override val primaryKey = PrimaryKey(id)
    val createdAt = datetime("created_at")
    val lastSeen = datetime("last_seen")
    val email = text("email")
    val givenName = text("given_name")
    val familyName = text("family_name")
    val moodleUsername = text("moodle_username").nullable()
    val idMigrationDone = bool("id_migration_done")
    val preMigrationId = text("pre_migration_id").nullable()
    val isTeacher = bool("is_teacher")
    val isStudent = bool("is_student")
    val isAdmin = bool("is_admin")
}


object Exercise : LongIdTable("exercise") {
    val dir = reference("dir_id", Dir).nullable()
    val owner = reference("owned_by_id", Account)
    val createdAt = datetime("created_at")
    val public = bool("public")
    val anonymousAutoassessEnabled = bool("anonymous_autoassess_enabled")
    val anonymousAutoassessTemplate = text("anonymous_autoassess_template").nullable()
    val successfulAnonymousSubmissionCount = integer("successful_anonymous_submission_count")
    val unsuccessfulAnonymousSubmissionCount = integer("unsuccessful_anonymous_submission_count")
    val removedSubmissionsCount = integer("removed_submissions_count")
}

object ExerciseVer : LongIdTable("exercise_version") {
    val exercise = reference("exercise_id", Exercise)
    val author = reference("author_id", Account)
    val previous = reference("previous_id", ExerciseVer).nullable()
    val autoExerciseId = reference("auto_exercise_id", AutoExercise).nullable()
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val graderType = enumerationByName("grader_type", 20, GraderType::class)
    val aasId = text("aas_id").nullable()
    val title = text("title")
    val textHtml = text("text_html").nullable()
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
    val allowedUses  = integer("allowed_uses")
    val usedCount  = integer("used_count")
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
    val instructionsAdoc = text("instructions_adoc").nullable()
    val titleAlias = text("title_alias").nullable()
    val moodleExId = text("moodle_exercise_id").nullable()
}

object TeacherCourseAccess : Table("teacher_course_access") {
    val teacher = reference("teacher_id", Account)
    val course = reference("course_id", Course)
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(teacher, course)
}

object TeacherCourseGroup : Table("teacher_course_group_access") {
    val teacher = reference("teacher_id", TeacherCourseAccess.teacher)
    val course = reference("course_id", TeacherCourseAccess.course)
    val courseGroup = reference("group_id", CourseGroup)
    override val primaryKey = PrimaryKey(teacher, course, courseGroup)
}

object StudentCourseAccess : Table("student_course_access") {
    val student = reference("student_id", Account)
    val course = reference("course_id", Course)
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(student, course)
}

object StudentCourseGroup : Table("student_course_group_access") {
    val student = reference("student_id", StudentCourseAccess.student)
    val course = reference("course_id", StudentCourseAccess.course)
    val courseGroup = reference("group_id", CourseGroup)
    override val primaryKey = PrimaryKey(student, course, courseGroup)
}

object StudentMoodlePendingAccess : Table("student_moodle_pending_access") {
    val course = reference("course_id", Course)
    val moodleUsername = text("moodle_username")
    val email = text("email")
    override val primaryKey = PrimaryKey(course, moodleUsername)
}

object StudentMoodlePendingCourseGroup : Table("student_moodle_pending_course_group_access") {
    val moodleUsername = reference("moodle_username", StudentMoodlePendingAccess.moodleUsername)
    val course = reference("course_id", StudentMoodlePendingAccess.course)
    val courseGroup = reference("group_id", CourseGroup)
    override val primaryKey = PrimaryKey(moodleUsername, course, courseGroup)
}

object StudentPendingAccess : Table("student_pending_access") {
    val course = reference("course_id", Course)
    val email = text("email")
    val validFrom = datetime("valid_from")
    override val primaryKey = PrimaryKey(course, email)
}

object StudentPendingCourseGroup : Table("student_pending_course_group_access") {
    val email = reference("email", StudentPendingAccess.email)
    val course = reference("course_id", StudentPendingAccess.course)
    val courseGroup = reference("group_id", CourseGroup)
    override val primaryKey = PrimaryKey(email, course, courseGroup)
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

object TeacherAssessment : LongIdTable("teacher_assessment") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_id", Account)
    val submission = reference("submission_id", Submission)
    val teacher = reference("teacher_id", Account)
    val mergeWindowStart = datetime("merge_window_start")
    val editedAt = datetime("edited_at").nullable()
    val grade = integer("grade").nullable()
    val feedbackHtml = text("feedback_html").nullable()
    val feedbackAdoc = text("feedback_adoc").nullable()
}

object AutomaticAssessment : LongIdTable("automatic_assessment") {
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

object ManagementNotification : LongIdTable("management_notification") {
    val message = text("message")
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
}

object SystemConfiguration : IdTable<String>("system_configuration") {
    override val id: Column<EntityID<String>> = text("key").entityId()
    override val primaryKey = PrimaryKey(id)
    val value = text("value")
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
    val public = bool("public")
}

object ArticleVersion : LongIdTable("article_version") {
    val article = reference("article_id", Article)
    val previous = reference("previous_id", ArticleVersion).nullable()
    val author = reference("author_id", Account)
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val title = text("title")
    val textHtml = text("text_html").nullable()
    val textAdoc = text("text_adoc").nullable()
}

object ArticleAlias : IdTable<String>("article_alias") {
    override val id: Column<EntityID<String>> = text("alias").entityId()
    override val primaryKey = PrimaryKey(id)
    val article = reference("article_id", Article)
    val createdAt = datetime("created_at")
    val owner = reference("created_by_id", Account)
}

object StoredFile : IdTable<String>("stored_file") {
    override val id: Column<EntityID<String>> = text("id").entityId()
    override val primaryKey = PrimaryKey(id)
    val exercise = reference("exercise_id", Exercise).nullable()
    val article = reference("article_id", Article).nullable()
    val usageConfirmed = bool("usage_confirmed")
    val type = text("type")
    val sizeBytes = long("size_bytes")
    val filename = text("filename")
    val data = binary("data")
    val createdAt = datetime("created_at")
    val owner = reference("created_by_id", Account)
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