package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Admin
import core.db.Student
import core.db.Teacher
import core.db.insertOrUpdate
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UpdateAccountController {

    data class PersonalDataBody(@JsonProperty("email", required = true) val email: String,
                                @JsonProperty("first_name", required = true) val firstName: String,
                                @JsonProperty("last_name", required = true) val lastName: String)

    @PostMapping("/account/personal")
    fun updateAccount(@RequestBody dto: PersonalDataBody, caller: EasyUser) {

        val account = Account(caller.id, dto.email,
                correctNameCapitalisation(dto.firstName), correctNameCapitalisation(dto.lastName))
        log.debug { "Update personal data for $account" }

        if (caller.isStudent()) {
            log.debug { "Update student ${caller.id}" }
            updateStudent(account)
        }
        if (caller.isTeacher()) {
            log.debug { "Update teacher ${caller.id}" }
            updateTeacher(account)
        }
        if (caller.isAdmin()) {
            log.debug { "Update admin ${caller.id}" }
            updateAdmin(account)
            // Admins should also have a teacher entity to add assessments, exercises etc
            updateTeacher(account)
        }
    }
}

data class Account(val username: String, val email: String, val givenName: String, val familyName: String)

private fun correctNameCapitalisation(name: String) =
        name.split(Regex(" +"))
                .joinToString(separator = " ") {
                    it.split("-").joinToString(separator = "-") {
                        it.toLowerCase().capitalize()
                    }
                }

private fun updateStudent(student: Account) {
    transaction {
        Student.insertOrUpdate(Student.id, listOf(Student.id, Student.createdAt)) {
            it[id] = EntityID(student.username, Student)
            it[email] = student.email
            it[givenName] = student.givenName
            it[familyName] = student.familyName
            it[createdAt] = DateTime.now()
        }
    }
}

private fun updateTeacher(teacher: Account) {
    transaction {
        Teacher.insertOrUpdate(Teacher.id, listOf(Teacher.id, Teacher.createdAt)) {
            it[id] = EntityID(teacher.username, Teacher)
            it[email] = teacher.email
            it[givenName] = teacher.givenName
            it[familyName] = teacher.familyName
            it[createdAt] = DateTime.now()
        }
    }
}

private fun updateAdmin(admin: Account) {
    transaction {
        Admin.insertOrUpdate(Admin.id, listOf(Admin.id, Admin.createdAt)) {
            it[id] = EntityID(admin.username, Admin)
            it[email] = admin.email
            it[givenName] = admin.givenName
            it[familyName] = admin.familyName
            it[createdAt] = DateTime.now()
        }
    }
}
