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
                dto.email.toLowerCase(),
                correctNameCapitalisation(dto.firstName),
                correctNameCapitalisation(dto.lastName))

        log.debug { "Update personal data for $account" }
        updateAccount(account, cacheInvalidator)

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

data class AccountData(val username: String, val email: String, val givenName: String, val familyName: String)

private fun correctNameCapitalisation(name: String) =
        name.split(Regex(" +"))
                .joinToString(separator = " ") {
                    it.split("-").joinToString(separator = "-") {
                        it.toLowerCase().capitalize()
                    }
                }

private fun updateAccount(accountData: AccountData, cacheInvalidator: CacheInvalidator) {
    transaction {
        Account.insertOrUpdate(Account.id, listOf(Account.id, Account.createdAt)) {
            it[id] = EntityID(accountData.username, Account)
            it[email] = accountData.email
            it[givenName] = accountData.givenName
            it[familyName] = accountData.familyName
            it[createdAt] = DateTime.now()
        }
    }

    cacheInvalidator.invalidateTotalUserCache()
}

private fun updateStudent(student: AccountData) {
    transaction {
        val studentId = Student.insertIgnoreAndGetId {
            it[id] = EntityID(student.username, Student)
            it[createdAt] = DateTime.now()
        }!!.value


        val otherCoursePendingIds = Join(Student, StudentPendingAccess,
                onColumn = Student.id,
                otherColumn = StudentPendingAccess.email,
                joinType = JoinType.INNER,
                additionalConstraint = { Student.id eq studentId })
                .slice(StudentPendingAccess.course)
                .selectAll()
                .mapNotNull { it[StudentPendingAccess.course].value }
                .toSet()


        val moodlePendingQuery = Join(Student, StudentMoodlePendingAccess,
                onColumn = Student.id,
                otherColumn = StudentMoodlePendingAccess.utUsername,
                joinType = JoinType.INNER,
                additionalConstraint = { Student.id eq studentId })
                .slice(StudentMoodlePendingAccess.course, StudentMoodlePendingAccess.utUsername)


        val utUsrname = moodlePendingQuery
                .selectAll()
                .mapNotNull { it[StudentMoodlePendingAccess.utUsername] }
                .firstOrNull()


        val moodleCourseIdsToAddAccess = when (utUsrname) {
            null -> emptySet()
            else -> {
                Student.insertOrUpdate(Student.id, listOf(Student.createdAt)) {
                    it[id] = EntityID(student.username, Student)
                    it[createdAt] = DateTime.now()
                    it[moodleUsername] = utUsrname
                }
                moodlePendingQuery.selectAll().mapNotNull { it[StudentMoodlePendingAccess.course].value }.toSet()
            }
        }


        for (course in (moodleCourseIdsToAddAccess union otherCoursePendingIds)) {
            StudentCourseAccess.insertIgnore {
                it[StudentCourseAccess.student] = EntityID(studentId, Student)
                it[StudentCourseAccess.course] = EntityID(course, Course)
            }
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

