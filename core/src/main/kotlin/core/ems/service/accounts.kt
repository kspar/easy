package core.ems.service

import core.db.Account
import core.db.Teacher
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


fun getUsernameByEmail(email: String): String? {
    return transaction {
        Account.slice(Account.id)
                .select {
                    Account.email eq email
                }.map {
                    it[Account.id].value
                }.singleOrNull()
    }
}

fun teacherExists(username: String): Boolean {
    return transaction {
        Teacher.select {
            Teacher.id eq username
        }.count() > 0
    }
}

fun accountExists(username: String): Boolean {
    return transaction {
        Account.select {
            Account.id eq username
        }.count() > 0
    }
}

