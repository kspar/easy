package ee.urgas.ems.dao

import ee.urgas.ems.model.GraderType
import org.jetbrains.exposed.dao.LongIdTable


object Teacher : LongIdTable() {
    val createdAt = datetime("created_at")
    val email = text("email")
    val givenName = text("given_name")
    val familyName = text("family_name")
}

object Exercise : LongIdTable() {
    val owner = reference("owned_by", Teacher)
    val createdAt = datetime("created_at")
    val public = bool("public")
}

object ExerciseVer : LongIdTable() {
    val exercise = reference("exercise_id", Exercise)
    val author = reference("author_id", Teacher)
    val previous = reference("previous_id", ExerciseVer).nullable()
    val validFrom = datetime("valid_from")
    val validTo = datetime("valid_to").nullable()
    val graderType = enumerationByName("grader_type", 20, GraderType::class)
    val aasId = long("aas_id").nullable()
    val title = text("title")
    val textHtml = text("text_html")
}

object Course : LongIdTable() {
    val createdAt = datetime("created_at")
    val title = text("title")
}

object CourseExercise : LongIdTable() {
    val course = reference("course_id", Course)
    val exercise = reference("exercise_id", Exercise)
    val gradeThreshold = integer("grade_threshold")
    val softDeadline = datetime("soft_deadline").nullable()
    val hardDeadline = datetime("hard_deadline").nullable()
    val orderIdx = integer("ordering_index")
    val studentVisible = bool("student_visible")
    val assessmentsStudentVisible = bool("assessments_student_visible")
}

object TeacherCourseAccess : LongIdTable() {
    val teacher = reference("teacher_id", Teacher)
    val course = reference("course_id", Course)
}

object Student : LongIdTable() {
    val createdAt = datetime("created_at")
    val email = text("email")
    val givenName = text("given_name")
    val familyName = text("family_name")
}

object StudentCourseAccess : LongIdTable() {
    val student = reference("student_id", Teacher)
    val course = reference("course_id", Course)
}

object Submission : LongIdTable() {
    val courseExercise = reference("course_exercise_id", CourseExercise)
    val student = reference("student_id", Student)
    val createdAt = datetime("created_at")
    val solution = text("solution")
}

object TeacherAssessment : LongIdTable() {
    val submission = reference("submission_id", Submission)
    val teacher = reference("teacher_id", Teacher)
    val createdAt = datetime("created_at")
    val grade = integer("grade")
    val feedback = text("feedback").nullable()
}

object AutomaticAssessment : LongIdTable() {
    val submission = reference("submission_id", Submission)
    val createdAt = datetime("created_at")
    val grade = integer("grade")
    val feedback = text("feedback").nullable()
}

