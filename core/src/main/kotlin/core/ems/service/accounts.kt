package core.ems.service

import core.db.Account
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun getUsernameByEmail(email: String): String? = transaction {
    Account.slice(Account.id)
        .select {
            Account.email eq email
        }.map {
            it[Account.id].value
        }.singleOrNull()
}

fun teacherExists(username: String): Boolean = transaction {
    Account.select {
        Account.id eq username and Account.isTeacher
    }.count() > 0
}

fun accountExists(username: String): Boolean = transaction {
    Account.select {
        Account.id eq username
    }.count() > 0
}

