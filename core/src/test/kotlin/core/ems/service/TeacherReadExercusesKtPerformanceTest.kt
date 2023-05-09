package core.ems.service

import core.db.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.FileSystemResourceAccessor
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeout
import javax.sql.DataSource


class TeacherReadExercusesKtPerformanceTest {
    private val embeddedPostgres: EmbeddedPostgres = EmbeddedPostgres.start()
    private val dataSource: DataSource = embeddedPostgres.postgresDatabase

    private val courseId = 1L
    private val numberOfExercises = 100
    private val numberOfStudents = 10000
    private val numberOfStudentTriesPerExercise = 3

    @Test
    fun `selectAllCourseExercisesLatestSubmissions should return under 5 seconds`() {
        assertTimeout(java.time.Duration.ofSeconds(5)) { selectAllCourseExercisesLatestSubmissions(courseId) }
    }

    @BeforeEach
    fun bootstrap() {
        Database.connect(dataSource)
        Liquibase(
            "db/changelog.xml",
            FileSystemResourceAccessor(),
            JdbcConnection(dataSource.connection)
        ).update("development")


        transaction {
            val ids = (1..numberOfStudents).map { it.toString() }
            val time = DateTime.now()

            Course.insert {
                it[id] = courseId
                it[title] = "Test Course"
                it[alias] = "TC"
                it[createdAt] = DateTime.now()
                it[moodleShortName] = "TCSN"
                it[moodleSyncStudents] = false
                it[moodleSyncGrades] = false
                it[moodleSyncStudentsInProgress] = false
                it[moodleSyncGradesInProgress] = false
            }

            Account.batchInsert(ids) {
                this[Account.id] = it
                this[Account.email] = "$it@example.com"
                this[Account.givenName] = "John$it"
                this[Account.familyName] = "Doe$it"
                this[Account.createdAt] = time
                this[Account.lastSeen] = time
                this[Account.idMigrationDone] = true
            }

            Student.batchInsert(ids) {
                this[Student.id] = it
                this[Student.createdAt] = time
            }

            StudentCourseAccess.batchInsert(ids) {
                this[StudentCourseAccess.student] = it
                this[StudentCourseAccess.course] = courseId
                this[StudentCourseAccess.createdAt] = time
            }


            Teacher.insert {
                it[id] = ids.first()
                it[createdAt] = time
            }

            TeacherCourseAccess.insert {
                it[teacher] = ids.first()
                it[course] = courseId
                it[createdAt] = time
            }

            Dir.insert {
                it[id] = ids.first().toLong()
                it[name] = "1"
                it[isImplicit] = true
                it[createdAt] = time
                it[modifiedAt] = DateTime.now()
            }

            Exercise.batchInsert(ids.take(numberOfExercises)) {
                this[Exercise.id] = it.toLong()
                this[Exercise.owner] = ids.first()
                this[Exercise.createdAt] = DateTime.now()
                this[Exercise.public] = true
                this[Exercise.anonymousAutoassessEnabled] = false
                this[Exercise.successfulAnonymousSubmissionCount] = 0
                this[Exercise.unsuccessfulAnonymousSubmissionCount] = 0
                this[Exercise.removedSubmissionsCount] = 0
                this[Exercise.dir] = EntityID(ids.first().toLong(), Dir)
            }


            ExerciseVer.batchInsert(ids.take(numberOfExercises)) {
                this[ExerciseVer.exercise] = it.toLong()
                this[ExerciseVer.author] = ids.first()
                this[ExerciseVer.validFrom] = time
                this[ExerciseVer.graderType] = GraderType.TEACHER
                this[ExerciseVer.title] = "Exercise $it"
                this[ExerciseVer.textHtml] = "<p>Exercise $it description</p>"
                this[ExerciseVer.textAdoc] = "Exercise $it description"
            }



            CourseExercise.batchInsert(ids.take(numberOfExercises)) {
                this[CourseExercise.id] = it.toLong()
                this[CourseExercise.course] = courseId
                this[CourseExercise.exercise] = it.toLong()
                this[CourseExercise.createdAt] = time
                this[CourseExercise.modifiedAt] = time
                this[CourseExercise.gradeThreshold] = 90
                this[CourseExercise.studentVisibleFrom] = time
                this[CourseExercise.softDeadline] = time
                this[CourseExercise.hardDeadline] = time
                this[CourseExercise.orderIdx] = 1
                this[CourseExercise.assessmentsStudentVisible] = true
                this[CourseExercise.instructionsHtml] = "CE $it"
                this[CourseExercise.instructionsAdoc] = "CE $it"
                this[CourseExercise.titleAlias] = "CE $it"
            }


            repeat(numberOfStudentTriesPerExercise) {
                ids.take(numberOfExercises).forEach { ceId ->
                    Submission.batchInsert(ids) {
                        this[Submission.courseExercise] = ceId.toLong()
                        this[Submission.student] = it
                        this[Submission.createdAt] = DateTime.now()
                        this[Submission.solution] = "submission $it"
                        this[Submission.autoGradeStatus] = AutoGradeStatus.NONE
                        this[Submission.grade] = 71
                        this[Submission.isAutoGrade] = false
                    }
                }
            }
        }
    }

    @AfterEach
    fun shutdown() {
        embeddedPostgres.close()
    }
}

