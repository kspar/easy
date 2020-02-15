package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.CacheInvalidator
import core.ems.service.PrivateCachingService
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
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
class UpdateAccountController(val privateCachingService: PrivateCachingService, val cacheInvalidator: CacheInvalidator) {

    data class PersonalDataBody(@JsonProperty("first_name", required = true)
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
                caller.email.toLowerCase(),
                correctNameCapitalisation(dto.firstName),
                correctNameCapitalisation(dto.lastName))

        log.debug { "Update personal data for $account" }
        val isChanged = updateAccount(account, privateCachingService)

        if (isChanged) {
            cacheInvalidator.invalidateTotalUserCache()
            cacheInvalidator.invalidateAccountCache(account.username)
        }

        if (caller.isStudent()) {
            log.debug { "Update student ${caller.id}" }
            updateStudent(account)
            if (isChanged) {
                updateStudentCourseAccesses(account, cacheInvalidator)
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

private fun updateAccount(accountData: AccountData, privateCachingService: PrivateCachingService): Boolean {
    return transaction {

        val oldAccount = privateCachingService.selectAccount(accountData.username)

        if (oldAccount == null) {
            Account.insert {
                it[id] = EntityID(accountData.username, Account)
                it[email] = accountData.email
                it[moodleUsername] = accountData.moodleUsername
                it[givenName] = accountData.givenName
                it[familyName] = accountData.familyName
                it[createdAt] = DateTime.now()
            }
            true

        } else {

            val isChanged = oldAccount.email != accountData.email ||
                    accountData.moodleUsername != null && accountData.moodleUsername != oldAccount.moodleUsername

            Account.update({ Account.id eq accountData.username }) {
                it[email] = accountData.email
                if (accountData.moodleUsername != null) {
                    it[moodleUsername] = accountData.moodleUsername
                }
                it[givenName] = accountData.givenName
                it[familyName] = accountData.familyName
            }

            isChanged
        }
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

private fun updateStudentCourseAccesses(accountData: AccountData, cacheInvalidator: CacheInvalidator) {
    log.debug { "Updating student course accesses" }

    val student = EntityID(accountData.username, Student)

    transaction {
        val pendingIdsToCourseIds = StudentPendingAccess
                .slice(StudentPendingAccess.id, StudentPendingAccess.course)
                .select { StudentPendingAccess.email eq accountData.email }
                .map { it[StudentPendingAccess.id] to it[StudentPendingAccess.course] }

        pendingIdsToCourseIds.forEach { (pendingAccessId, courseId) ->
            log.debug { "Granting access for ${accountData.username} to course $courseId" }
            val accessId = StudentCourseAccess.insertAndGetId {
                it[StudentCourseAccess.student] = student
                it[StudentCourseAccess.course] = courseId
            }

            val groupIds = StudentPendingGroup
                    .slice(StudentPendingGroup.group)
                    .select { StudentPendingGroup.pendingAccess eq pendingAccessId }
                    .map { it[StudentPendingGroup.group] }

            StudentGroupAccess.batchInsert(groupIds) {
                this[StudentGroupAccess.student] = student.value
                this[StudentGroupAccess.course] = courseId.value
                this[StudentGroupAccess.group] = it
                this[StudentGroupAccess.courseAccess] = accessId
            }
        }

        StudentPendingAccess.deleteWhere {
            StudentPendingAccess.id inList pendingIdsToCourseIds.map { it.first }
        }


        data class MoodlePendingAccess(val id: Long, val courseId: Long, val moodleUsername: String)

        val moodlePendingQuery = StudentMoodlePendingAccess
                .select { StudentMoodlePendingAccess.email eq accountData.email }
        if (accountData.moodleUsername != null) {
            moodlePendingQuery.orWhere { StudentMoodlePendingAccess.moodleUsername eq accountData.moodleUsername }
        }

        val pendingAccesses = moodlePendingQuery
                .map {
                    MoodlePendingAccess(
                            it[StudentMoodlePendingAccess.id].value,
                            it[StudentMoodlePendingAccess.course].value,
                            it[StudentMoodlePendingAccess.moodleUsername]
                    )
                }

        pendingAccesses.forEach { pendingAccess ->
            log.debug { "Granting access for ${accountData.username} to course ${pendingAccess.courseId} based on Moodle pending access $pendingAccess" }

            // Updates moodleUsername for every pending access - not ideal but unlikely to cause problems
            Account.update({ Account.id eq accountData.username }) {
                it[moodleUsername] = pendingAccess.moodleUsername
            }
            // Clear cache for this account
            cacheInvalidator.invalidateAccountCache(accountData.username)

            val accessId = StudentCourseAccess.insertAndGetId {
                it[StudentCourseAccess.student] = student
                it[StudentCourseAccess.course] = EntityID(pendingAccess.courseId, Course)
            }

            val groupIds = StudentMoodlePendingGroup
                    .slice(StudentMoodlePendingGroup.group)
                    .select { StudentMoodlePendingGroup.pendingAccess eq pendingAccess.id }
                    .map { it[StudentMoodlePendingGroup.group] }

            StudentGroupAccess.batchInsert(groupIds) {
                this[StudentGroupAccess.student] = student.value
                this[StudentGroupAccess.course] = pendingAccess.courseId
                this[StudentGroupAccess.group] = it
                this[StudentGroupAccess.courseAccess] = accessId
            }
        }

        StudentMoodlePendingAccess.deleteWhere {
            StudentMoodlePendingAccess.id inList pendingAccesses.map { it.id }
        }

    }
}

