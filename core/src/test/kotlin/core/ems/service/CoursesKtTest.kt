package core.ems.service

import core.db.Course
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
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

    @Test
    fun `getCourse should return correct course when course exists`() {
        val course = getCourse(1)
        assertEquals("Test Course", course?.title)
        assertEquals("TC", course?.alias)
    }

    @Test
    fun `getCourse should return null when course does not exist`() {
        val course = getCourse(2)
        assertNull(course)
    }

    @BeforeEach
    fun bootstrap() {
        Database.connect(dataSource)

        transaction {
            addLogger(StdOutSqlLogger)
            SchemaUtils.create(Course)
            Course.insert {
                it[id] = EntityID(1, Course)
                it[title] = "Test Course"
                it[alias] = "TC"
                it[createdAt] = DateTime.now()
                it[moodleShortName] = "TCSN"
                it[moodleSyncStudents] = true
                it[moodleSyncGrades] = true
                it[moodleSyncStudentsInProgress] = true
                it[moodleSyncGradesInProgress] = true
            }
        }
    }

    @AfterEach
    fun shutdown() {
        embeddedPostgres.close()
    }
}

