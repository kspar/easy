package ee.urgas.ems.db

import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IdTable
import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.Column


object Teacher : IdTable<String>("teacher") {
    override val id: Column<EntityID<String>> = text("email").primaryKey().entityId()
    val createdAt = datetime("created_at")
    val givenName = text("given_name")
    val familyName = text("family_name")
}

object Exercise : LongIdTable("exercise") {
    val owner = reference("owned_by", Teacher)
    val createdAt = datetime("created_at")
    val public = bool("public")
}

object ExerciseVer : LongIdTable("exercise_version") {
    val exercise = reference("exercise_id", Exercise)
    val author = reference("author", Teacher)
    val previous = reference("previous_id", ExerciseVer).nullable()
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val graderType = enumerationByName("grader_type", 20, GraderType::class)
    val aasId = long("aas_id").nullable()
    val title = text("title")
    val textHtml = text("text_html")
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
}

object TeacherCourseAccess : LongIdTable("teacher_course_access") {
    val teacher = reference("teacher_email", Teacher)
    val course = reference("course_id", Course)
}

object Student : IdTable<String>("student") {
    override val id: Column<EntityID<String>> = text("email").primaryKey().entityId()
    val createdAt = datetime("created_at")
    val givenName = text("given_name")
    val familyName = text("family_name")
}

object StudentCourseAccess : LongIdTable("student_course_access") {
    val student = reference("student_email", Student)
    val course = reference("course_id", Course)
}

object Submission : LongIdTable("submission") {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_email", Student)
    val createdAt = datetime("created_at")
    val solution = text("solution")
}

object TeacherAssessment : LongIdTable("teacher_assessment") {
    val submission = reference("submission_id", Submission)
    val teacher = reference("teacher_email", Teacher)
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

