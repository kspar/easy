package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Account
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update


fun getUsernameByEmail(email: String): String? = transaction {
    Account
        .select(Account.id)
        .where { Account.email eq email }
        .map { it[Account.id].value }
        .singleOrNull()
}

fun teacherExists(username: String): Boolean = transaction {
    Account.selectAll().where { Account.id eq username and Account.isTeacher }.count() > 0
}

data class TeacherResp(
    @get:JsonProperty("id")
    val id: String,
    @get:JsonProperty("given_name")
    val givenName: String,
    @get:JsonProperty("family_name")
    val familyName: String
)


fun selectTeacher(teacherId: String) = transaction {
    Account
        .select(Account.id, Account.givenName, Account.familyName)
        .where { Account.id eq teacherId and Account.isTeacher }
        .map { TeacherResp(it[Account.id].value, it[Account.givenName], it[Account.familyName]) }
        .singleOrInvalidRequest()
}

fun selectPseudonym(username: String): String = transaction {
    Account
        .select(Account.pseudonym)
        .where { Account.id eq username }
        .map { it[Account.pseudonym] }
        .single()
}

fun insertTeacher(teacherId: String) {
    transaction {
        Account.update({ Account.id eq teacherId }) {
            it[Account.isTeacher] = true
        }
    }
}


