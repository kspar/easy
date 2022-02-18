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
}

object Student : IdTable<String>("student") {
    override val id = reference("username", Account)
    override val primaryKey = PrimaryKey(id)
    val createdAt = datetime("created_at")
}

object Teacher : IdTable<String>("teacher") {
    override val id = reference("username", Account)
    override val primaryKey = PrimaryKey(id)
    val createdAt = datetime("created_at")
}

object Admin : IdTable<String>("admin") {
    override val id = reference("username", Account)
    override val primaryKey = PrimaryKey(id)
    val createdAt = datetime("created_at")
}

object Exercise : LongIdTable("exercise") {
    val dir = reference("dir_id", Dir).nullable()
    val owner = reference("owned_by_id", Teacher)
    val createdAt = datetime("created_at")
    val public = bool("public")
}

object ExerciseVer : LongIdTable("exercise_version") {
    val exercise = reference("exercise_id", Exercise)
    val author = reference("author_id", Teacher)
    val previous = reference("previous_id", ExerciseVer).nullable()
    val autoExerciseId = reference("auto_exercise_id", AutoExercise).nullable()
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val graderType = enumerationByName("grader_type", 20, GraderType::class)
    val aasId = text("aas_id").nullable()
    val title = text("title")
    val textHtml = text("text_html").nullable()
    val textAdoc = text("text_adoc").nullable()
}

object Course : LongIdTable("course") {
    val createdAt = datetime("created_at")
    val title = text("title")
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
    val teacher = reference("teacher_id", Teacher)
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
    val student = reference("student_id", Student)
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
    val student = reference("student_id", Student)
    val createdAt = datetime("created_at")
    val solution = text("solution")
    val autoGradeStatus = enumerationByName("auto_grade_status", 20, AutoGradeStatus::class)
}

object TeacherAssessment : LongIdTable("teacher_assessment") {
    val submission = reference("submission_id", Submission)
    val teacher = reference("teacher_id", Teacher)
    val createdAt = datetime("created_at")
    val grade = integer("grade")
    val feedback = text("feedback").nullable()
}

object AutomaticAssessment : LongIdTable("automatic_assessment") {
    val submission = reference("submission_id", Submission)
    val createdAt = datetime("created_at")
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
    val student = reference("student_id", Student)
    val createdAt = datetime("created_at")
    val solution = text("solution")
    override val primaryKey = PrimaryKey(courseExercise, student)
}

object TeacherSubmission : LongIdTable("teacher_submission") {
    val teacher = reference("teacher_id", Teacher)
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
    val owner = reference("owner_id", Admin)
    val createdAt = datetime("created_at")
    val public = bool("public")
}

object ArticleVersion : LongIdTable("article_version") {
    val article = reference("article_id", Article)
    val previous = reference("previous_id", ArticleVersion).nullable()
    val author = reference("author_id", Admin)
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
    val owner = reference("created_by_id", Admin)
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
    val owner = reference("created_by_id", Teacher)
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
    val anyAccess = enumerationByName("any_account_access_level", 10, DirAccessLevel::class).nullable()
    val createdAt = datetime("created_at")
    val modifiedAt = datetime("modified_at")
}

object GroupDirAccess : Table("group_exercise_dir_access") {
    val group = reference("group_id", Group)
    val dir = reference("dir_id", Dir)
    val level = enumerationByName("access_level", 10, DirAccessLevel::class)
    val createdAt = datetime("created_at")
    override val primaryKey = PrimaryKey(group, dir)
}