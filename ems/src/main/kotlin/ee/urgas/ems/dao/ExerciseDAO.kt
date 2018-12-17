package ee.urgas.ems.dao

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

fun createStudent(email: String, givenName: String, familyName: String) {
    transaction {
        Student.insert {
            it[Student.email] = email
            it[Student.givenName] = givenName
            it[Student.familyName] = familyName
            it[createdAt] = DateTime.now()
        }
    }
}
