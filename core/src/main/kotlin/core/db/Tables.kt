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
    val email = text("email")
    val givenName = text("given_name")
    val familyName = text("family_name")
    val moodleUsername = text("moodle_username").nullable()
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
    val moodleSyncGrades = bool("moodle_sync_grades")
}

object Group : LongIdTable("group") {
    val name = text("name")
    val course = reference("course_id", Course)
}

object CourseExercise : LongIdTable("course_exercise") {
    val course = reference("course_id", Course)
    val exercise = reference("exercise_id", Exercise)
    val gradeThreshold = integer("grade_threshold")
    val softDeadline = datetime("soft_deadline").nullable()
    val hardDeadline = datetime("hard_deadline").nullable()
    val orderIdx = integer("ordering_index")
    val studentVisible = bool("student_visible")
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

object TeacherGroupAccess : Table("teacher_group_access") {
    val teacher = reference("teacher_id", TeacherCourseAccess.teacher)
    val course = reference("course_id", TeacherCourseAccess.course)
    val group = reference("group_id", Group)
    override val primaryKey = PrimaryKey(teacher, course, group)
}

object StudentCourseAccess : LongIdTable("student_course_access") {
    // Should not have ID but composite key
    val student = reference("student_id", Student)//.primaryKey()
    val course = reference("course_id", Course)//.primaryKey()
    val createdAt = datetime("created_at")
}

object StudentGroupAccess : Table("student_group_access") {
    // Should not refer to StudentCourseAccess.id but to its composite key
    val student = text("student_id") //reference("student_id", StudentCourseAccess.student).primaryKey()
    val course = long("course_id") //reference("course_id", StudentCourseAccess.course).primaryKey()
    val courseAccess = reference("student_course_access_id", StudentCourseAccess)
    val group = reference("group_id", Group)
    override val primaryKey = PrimaryKey(courseAccess, group)
}

object StudentMoodlePendingAccess : LongIdTable("student_moodle_pending_access") {
    // Should not have ID but composite key
    val course = reference("course_id", Course)//.primaryKey()
    val moodleUsername = text("moodle_username")//.primaryKey()
    val email = text("email")
}

object StudentMoodlePendingGroup : Table("student_moodle_pending_group_access") {
    // Should not refer to StudentMoodlePendingAccess.id but to its composite key
    val moodleUsername = text("moodle_username") //reference("moodle_username", StudentMoodlePendingAccess.moodleUsername).primaryKey()
    val course = long("course_id") //reference("course_id", StudentMoodlePendingAccess.course).primaryKey()
    val pendingAccess = reference("student_moodle_pending_access_id", StudentMoodlePendingAccess)
    val group = reference("group_id", Group)
    override val primaryKey = PrimaryKey(pendingAccess, group)
}

object StudentPendingAccess : LongIdTable("student_pending_access") {
    // Should not have ID but composite key
    val course = reference("course_id", Course)//.primaryKey()
    val email = text("email")//.primaryKey()
    val validFrom = datetime("valid_from")
}

object StudentPendingGroup : Table("student_pending_group_access") {
    // Should not refer to StudentPendingAccess.id but to its composite key
    val email = text("email") //reference("email", StudentPendingAccess.email).primaryKey()
    val course = long("course_id") //reference("course_id", StudentPendingAccess.course).primaryKey()
    val pendingAccess = reference("student_pending_access_id", StudentPendingAccess)
    val group = reference("group_id", Group)
    override val primaryKey = PrimaryKey(pendingAccess, group)
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
    val containerImage = text("container_image")
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
    val load = integer("load")
    val maxLoad = integer("max_load")
}

object AutoExerciseExecutor : LongIdTable("auto_exercise_executor") {
    val autoExercise = reference("auto_exercise_id", AutoExercise)
    val executor = reference("executor_id", Executor)
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
