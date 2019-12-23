package core.ems.service

import core.db.Account
import core.db.Teacher
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


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

