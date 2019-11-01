package core.db

import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IdTable
import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table


object Account : IdTable<String>("account") {
    override val id: Column<EntityID<String>> = text("username").primaryKey().entityId()
    val createdAt = datetime("created_at")
    val email = text("email")
    val givenName = text("given_name")
    val familyName = text("family_name")
}

object Student : IdTable<String>("student") {
    override val id: Column<EntityID<String>> = reference("username", Account).primaryKey()
    val createdAt = datetime("created_at")
}

object Teacher : IdTable<String>("teacher") {
    override val id: Column<EntityID<String>> = reference("username", Account).primaryKey()
    val createdAt = datetime("created_at")
}

object Admin : IdTable<String>("admin") {
    override val id: Column<EntityID<String>> = reference("username", Account).primaryKey()
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
    val titleAlias = text("title_alias").nullable()
}

object TeacherCourseAccess : LongIdTable("teacher_course_access") {
    val teacher = reference("teacher_id", Teacher)
    val course = reference("course_id", Course)
}


object StudentCourseAccess : LongIdTable("student_course_access") {
    val student = reference("student_id", Student)
    val course = reference("course_id", Course)
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
    val courseExercise = reference("course_exercise_id", CourseExercise).primaryKey()
    val student = reference("student_id", Student).primaryKey()
    val createdAt = datetime("created_at")
    val solution = text("solution")
}

object TestingDraft : Table("testing_draft") {
    val courseExercise = reference("course_exercise_id", CourseExercise).primaryKey()
    val teacher = reference("teacher_id", Teacher).primaryKey()
    val createdAt = datetime("created_at")
    val solution = text("solution")
}

object SystemConfiguration : IdTable<String>("system_configuration") {
    override val id: Column<EntityID<String>> = text("key").primaryKey().entityId()
    val value = text("value")
}

object LogReport : LongIdTable("log_report") {
    val userId = reference("user_id", Account)
    val logTime = datetime("log_time")
    val logLevel = text("log_level")
    val logMessage = text("log_message")
    val clientId = text("client_id")
}