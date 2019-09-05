package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class UpdateAccountController {

    data class PersonalDataBody(@JsonProperty("email", required = true)
                                @field:NotBlank @field:Size(max = 254) val email: String,
                                @JsonProperty("first_name", required = true)
                                @field:NotBlank @field:Size(max = 100) val firstName: String,
                                @JsonProperty("last_name", required = true)
                                @field:NotBlank @field:Size(max = 100) val lastName: String)

    data class Resp(@JsonProperty("messages")
                    @JsonInclude(JsonInclude.Include.NON_NULL) val messages: List<MessageResp>)

    data class MessageResp(@JsonProperty("message") val message: String)

    @PostMapping("/account/checkin")
    fun controller(@Valid @RequestBody dto: PersonalDataBody, caller: EasyUser): Resp {

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

        return selectMessages()
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

private fun selectMessages(): UpdateAccountController.Resp {
    return transaction {
        UpdateAccountController.Resp(ManagementNotification.selectAll()
                .orderBy(ManagementNotification.id, SortOrder.DESC)
                .map {
                    UpdateAccountController.MessageResp(
                            it[ManagementNotification.message]
                    )
                })
    }
}

