package ee.urgas.ems.bl.register

import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.Teacher
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RegisterAccountController {

    // TODO: register -> update
    @PostMapping("/register")
    fun registerAccount(caller: EasyUser) {

        val account = Account(caller.id, caller.email, caller.givenName, caller.familyName)
        log.debug { "Register $account" }

        if (caller.isStudent()) {
            registerStudent(account)
        }
        if (caller.isTeacher()) {
            registerTeacher(account)
        }
    }
}


data class Account(val username: String, val email: String, val givenName: String, val familyName: String)


private fun registerStudent(student: Account) {
    if (!studentExists(student.username)) {
        log.debug { "Student ${student.username} not found, inserting" }
        insertStudent(student)
    } else {
        log.debug { "Student ${student.username} already exists" }
    }
}

private fun registerTeacher(teacher: Account) {
    if (!teacherExists(teacher.username)) {
        log.debug { "Teacher ${teacher.username} not found, inserting" }
        insertTeacher(teacher)
    } else {
        log.debug { "Teacher ${teacher.username} already exists" }
    }
}

private fun studentExists(id: String): Boolean {
    return transaction {
        Student.select { Student.id eq id }
                .count() == 1
    }
}

private fun teacherExists(id: String): Boolean {
    return transaction {
        Teacher.select { Teacher.id eq id }
                .count() == 1
    }
}

private fun insertStudent(student: Account) {
    transaction {
        Student.insert {
            it[id] = EntityID(student.email, Student)
            it[email] = student.email
            it[givenName] = student.givenName
            it[familyName] = student.familyName
            it[createdAt] = DateTime.now()
        }
    }
}

private fun insertTeacher(teacher: Account) {
    transaction {
        Teacher.insert {
            it[id] = EntityID(teacher.email, Teacher)
            it[email] = teacher.email
            it[givenName] = teacher.givenName
            it[familyName] = teacher.familyName
            it[createdAt] = DateTime.now()
        }
    }
}
