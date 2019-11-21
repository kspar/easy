package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.CacheInvalidator
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.annotation.Secured
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

    @Autowired
    lateinit var cacheInvalidator: CacheInvalidator

    data class PersonalDataBody(@JsonProperty("email", required = true)
                                @field:NotBlank @field:Size(max = 254) val email: String,
                                @JsonProperty("first_name", required = true)
                                @field:NotBlank @field:Size(max = 100) val firstName: String,
                                @JsonProperty("last_name", required = true)
                                @field:NotBlank @field:Size(max = 100) val lastName: String)

    data class Resp(@JsonProperty("messages")
                    @JsonInclude(JsonInclude.Include.NON_NULL) val messages: List<MessageResp>)

    data class MessageResp(@JsonProperty("message") val message: String)

    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/account/checkin")
    fun controller(@Valid @RequestBody dto: PersonalDataBody, caller: EasyUser): Resp {

        val account = AccountData(
                caller.id,
                caller.moodleUsername,
                dto.email.toLowerCase(),
                correctNameCapitalisation(dto.firstName),
                correctNameCapitalisation(dto.lastName))

        log.debug { "Update personal data for $account" }
        val isChanged = updateAccount(account)

        if (isChanged) {
            cacheInvalidator.invalidateTotalUserCache()
        }

        if (caller.isStudent()) {
            log.debug { "Update student ${caller.id}" }
            updateStudent(account)
            if (isChanged) {
                updateStudentCourseAccesses(account)
            }
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

data class AccountData(val username: String, val moodleUsername: String?, val email: String, val givenName: String, val familyName: String)

private fun correctNameCapitalisation(name: String) =
        name.split(Regex(" +"))
                .joinToString(separator = " ") {
                    it.split("-").joinToString(separator = "-") {
                        it.toLowerCase().capitalize()
                    }
                }

private fun updateAccount(accountData: AccountData): Boolean {
    return transaction {
        val isChanged = Account.select {
            (Account.id eq accountData.username) and
                    (Account.email eq accountData.email) and
                    (Account.moodleUsername eq accountData.moodleUsername)
        }.count() != 1

        Account.insertOrUpdate(Account.id, listOf(Account.id, Account.createdAt)) {
            it[id] = EntityID(accountData.username, Account)
            it[email] = accountData.email
            it[moodleUsername] = accountData.moodleUsername
            it[givenName] = accountData.givenName
            it[familyName] = accountData.familyName
            it[createdAt] = DateTime.now()
        }

        isChanged
    }
}

private fun updateStudent(student: AccountData) {
    transaction {
        Student.insertIgnore {
            it[id] = EntityID(student.username, Student)
            it[createdAt] = DateTime.now()
        }
    }
}

private fun updateTeacher(teacher: AccountData) {
    transaction {
        Teacher.insertIgnore {
            it[id] = EntityID(teacher.username, Teacher)
            it[createdAt] = DateTime.now()
        }
    }
}

private fun updateAdmin(admin: AccountData) {
    transaction {
        Admin.insertIgnore {
            it[id] = EntityID(admin.username, Admin)
            it[createdAt] = DateTime.now()
        }
    }
}

private fun selectMessages(): UpdateAccountController.Resp {
    return transaction {
        UpdateAccountController.Resp(
                ManagementNotification.selectAll()
                        .orderBy(ManagementNotification.id, SortOrder.DESC)
                        .map {
                            UpdateAccountController.MessageResp(
                                    it[ManagementNotification.message]
                            )
                        })
    }
}

private fun updateStudentCourseAccesses(accountData: AccountData) {
    log.debug { "Updating student course accesses" }
    transaction {
        val nonMoodleCoursePendingIds = (
                Join(Account innerJoin Student, StudentPendingAccess,
                        onColumn = Account.email,
                        otherColumn = StudentPendingAccess.email,
                        joinType = JoinType.INNER))
                .slice(StudentPendingAccess.course)
                .select { Account.id eq accountData.username }
                .mapNotNull { it[StudentPendingAccess.course].value }

        for (course in nonMoodleCoursePendingIds) {
            StudentCourseAccess.insertIgnore {
                it[StudentCourseAccess.student] = EntityID(accountData.username, Student)
                it[StudentCourseAccess.course] = EntityID(course, Course)
            }
        }

        StudentPendingAccess.deleteWhere {
            StudentPendingAccess.email eq accountData.email and
                    (StudentPendingAccess.course inList nonMoodleCoursePendingIds)
        }

        if (accountData.moodleUsername != null) {
            log.debug { "Updating student Moodle course accesses" }

            val moodleCoursePendingIds = (
                    Join(Account innerJoin Student, StudentMoodlePendingAccess,
                            onColumn = Account.moodleUsername,
                            otherColumn = StudentMoodlePendingAccess.moodleUsername,
                            joinType = JoinType.INNER))
                    .slice(StudentMoodlePendingAccess.course)
                    .select { Account.id eq accountData.username }
                    .mapNotNull { it[StudentMoodlePendingAccess.course].value }

            for (course in moodleCoursePendingIds) {
                StudentCourseAccess.insertIgnore {
                    it[StudentCourseAccess.student] = EntityID(accountData.username, Student)
                    it[StudentCourseAccess.course] = EntityID(course, Course)
                }
            }

            StudentMoodlePendingAccess.deleteWhere {
                StudentMoodlePendingAccess.moodleUsername eq accountData.moodleUsername and
                        (StudentMoodlePendingAccess.course inList moodleCoursePendingIds)
            }
        }
    }
}

