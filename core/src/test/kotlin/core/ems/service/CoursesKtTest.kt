package core.ems.service

import core.db.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import liquibase.Liquibase
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.FileSystemResourceAccessor
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.StdOutSqlLogger
import org.jetbrains.exposed.sql.addLogger
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.sql.DataSource

class CoursesKtTest {
    private val embeddedPostgres: EmbeddedPostgres = EmbeddedPostgres.start()
    private val dataSource: DataSource = embeddedPostgres.postgresDatabase

    private val teacher1Id = "teacher1"
    private val student1Id = "student1"
    private val student2Id = "student2"
    private val studentIds = listOf(student1Id, student2Id)

    private val exercise1Id = 1L
    private val course1Id = 1L
    private val exerciseVer1Id = 1L

    @Test
    fun `getCourse should return correct course when course exists`() {
        val course = getCourse(course1Id)
        assertEquals("Test Course", course?.title)
        assertEquals("TC", course?.alias)
    }

    @Test
    fun `selectStudentsOnCourse should return two students`() {
        val students = selectStudentsOnCourse(course1Id).map { it.id }
        assertEquals(students.size, 2)
        assertEquals(students.toSet(), studentIds.toSet())
    }

    @Test
    fun `getCourse should return null when course does not exist`() {
        val course = getCourse(course1Id + 1)
        assertNull(course)
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
            addLogger(StdOutSqlLogger)

            Account.insert {
                it[id] = EntityID(student1Id, Account)
                it[email] = "user1@example.com"
                it[givenName] = "John"
                it[familyName] = "Doe"
                it[createdAt] = DateTime.parse("2023-04-28T12:00:00Z")
                it[lastSeen] = DateTime.parse("2023-04-28T12:00:00Z")
                it[idMigrationDone] = true
            }

            Account.insert {
                it[id] = EntityID(student2Id, Account)
                it[email] = "user2@example.com"
                it[givenName] = "Jane"
                it[familyName] = "Doe"
                it[createdAt] = DateTime.parse("2023-04-27T12:00:00Z")
                it[lastSeen] = DateTime.parse("2023-04-27T12:00:00Z")
                it[idMigrationDone] = true
            }
            Account.insert {
                it[id] = EntityID(teacher1Id, Account)
                it[email] = "user3@example.com"
                it[givenName] = "Bob"
                it[familyName] = "Smith"
                it[createdAt] = DateTime.parse("2023-04-26T12:00:00Z")
                it[lastSeen] = DateTime.parse("2023-04-26T12:00:00Z")
                it[idMigrationDone] = true
            }

            Student.insert {
                it[id] = student1Id
                it[createdAt] = DateTime.parse("2023-04-28T12:00:00Z")
            }
            Student.insert {
                it[id] = student2Id
                it[createdAt] = DateTime.parse("2023-04-27T12:00:00Z")
            }

            Teacher.insert {
                it[id] = teacher1Id
                it[createdAt] = DateTime.parse("2023-04-26T12:00:00Z")
            }

            Dir.insert {
                it[id] = 1L
                it[name] = "1"
                it[isImplicit] = true
                it[createdAt] = DateTime.now()
                it[modifiedAt] = DateTime.now()
            }

            Exercise.insert {
                it[id] = exercise1Id
                it[owner] = EntityID(teacher1Id, Account)
                it[createdAt] = DateTime.now()
                it[public] = true
                it[anonymousAutoassessEnabled] = false
                it[successfulAnonymousSubmissionCount] = 0
                it[unsuccessfulAnonymousSubmissionCount] = 0
                it[removedSubmissionsCount] = 0
                it[dir] = EntityID(1L, Dir)
            }

            ExerciseVer.insert {
                it[id] = exerciseVer1Id
                it[exercise] = EntityID(exercise1Id, Exercise)
                it[author] = EntityID(teacher1Id, Account)
                it[validFrom] = DateTime.now().minusDays(1)
                it[graderType] = GraderType.TEACHER
                it[title] = "Exercise 1"
                it[textHtml] = "<p>Exercise 1 description</p>"
                it[textAdoc] = "Exercise 1 description"
            }

            Course.insert {
                it[id] = course1Id
                it[title] = "Test Course"
                it[alias] = "TC"
                it[createdAt] = DateTime.now()
                it[moodleShortName] = "TCSN"
                it[moodleSyncStudents] = false
                it[moodleSyncGrades] = false
                it[moodleSyncStudentsInProgress] = false
                it[moodleSyncGradesInProgress] = false
            }

            CourseExercise.insert {
                it[course] = course1Id
                it[exercise] = exercise1Id
                it[createdAt] = DateTime.now()
                it[modifiedAt] = DateTime.now()
                it[gradeThreshold] = 80
                it[studentVisibleFrom] = DateTime.now()
                it[softDeadline] = DateTime.now().plusDays(7)
                it[hardDeadline] = DateTime.now().plusDays(14)
                it[orderIdx] = 1
                it[assessmentsStudentVisible] = true
                it[instructionsHtml] = "<p>Course exercise instructions</p>"
                it[instructionsAdoc] = "Course exercise instructions"
                it[titleAlias] = "Course exercise title alias"
            }

            TeacherCourseAccess.insert {
                it[teacher] = teacher1Id
                it[course] = course1Id
                it[createdAt] = DateTime.now()
            }

            studentIds.forEach { id ->
                StudentCourseAccess.insert {
                    it[student] = id
                    it[course] = course1Id
                    it[createdAt] = DateTime.now()
                }
            }
        }
    }

    @AfterEach
    fun shutdown() {
        embeddedPostgres.close()
    }
}

