package ee.urgas.ems.bl.register

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
@RequestMapping("/v1")
class RegisterAccountController {

    @PostMapping("/register")
    fun registerAccount() {
        // TODO: get data from auth
        val email = "ford"
        val givenName = "Ford"
        val familyName = "Prefect"
        val roles = setOf("STUDENT", "TEACHER")

        if (roles.contains("STUDENT")) {
            val student = StudentAccount(email, givenName, familyName)
            log.debug { "Registering $student" }
            registerStudent(student)
        }
        if (roles.contains("TEACHER")) {
            val teacher = TeacherAccount(email, givenName, familyName)
            log.debug { "Registering $teacher" }
            registerTeacher(teacher)
        }
    }
}


data class StudentAccount(val email: String, val givenName: String, val familyName: String)
data class TeacherAccount(val email: String, val givenName: String, val familyName: String)



private fun registerStudent(student: StudentAccount) {
    if (!studentExists(student.email)) {
        log.debug { "Student not found, inserting" }
        insertStudent(student)
    } else {
        log.debug { "Student already exists" }
    }
}

private fun registerTeacher(teacher: TeacherAccount) {
    if (!teacherExists(teacher.email)) {
        log.debug { "Teacher not found, inserting" }
        insertTeacher(teacher)
    } else {
        log.debug { "Teacher already exists" }
    }
}

private fun studentExists(email: String): Boolean {
    return transaction {
        Student.select { Student.id eq email }
                .count() == 1
    }
}

private fun teacherExists(email: String): Boolean {
    return transaction {
        Teacher.select { Teacher.id eq email }
                .count() == 1
    }
}

private fun insertStudent(student: StudentAccount) {
    transaction {
        Student.insert {
            it[id] = EntityID(student.email, Student)
            it[givenName] = student.givenName
            it[familyName] = student.familyName
            it[createdAt] = DateTime.now()
        }
    }
}

private fun insertTeacher(teacher: TeacherAccount) {
    transaction {
        Teacher.insert {
            it[id] = EntityID(teacher.email, Teacher)
            it[givenName] = teacher.givenName
            it[familyName] = teacher.familyName
            it[createdAt] = DateTime.now()
        }
    }
}
