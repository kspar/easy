package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.cache.CacheInvalidator
import core.ems.service.cache.PrivateCachingService
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


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/account/checkin")
    fun controller(@Valid @RequestBody dto: PersonalDataBody, caller: EasyUser) {

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
            if (!privateCachingService.studentExists(account.username)) {
                log.debug { "Update student ${caller.id}" }
                updateStudent(account)
            }
            if (isChanged) {
                log.debug { "Update student course access ${caller.id}" }
                updateStudentCourseAccesses(account)
                cacheInvalidator.invalidateAccountCache(account.username)
            }
        }

        if (caller.isTeacher() && !privateCachingService.teacherExists(account.username)) {
            log.debug { "Update teacher ${caller.id}" }
            updateTeacher(account)
        }

        if (caller.isAdmin() && !privateCachingService.adminExists(account.username)) {
            log.debug { "Update admin ${caller.id}" }
            updateAdmin(account)
            // Admins should also have a teacher entity to add assessments, exercises etc
            updateTeacher(account)
        }
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
            insertAccount(accountData)
            true

        } else {

            val isChanged = oldAccount.email != accountData.email ||
                    oldAccount.givenName != accountData.givenName ||
                    oldAccount.familyName != accountData.familyName ||
                    accountData.moodleUsername != null && accountData.moodleUsername != oldAccount.moodleUsername

            if (isChanged) {
                Account.update({ Account.id eq accountData.username }) {
                    it[email] = accountData.email
                    if (accountData.moodleUsername != null) {
                        it[moodleUsername] = accountData.moodleUsername
                    }
                    it[givenName] = accountData.givenName
                    it[familyName] = accountData.familyName
                }
            }

            isChanged
        }
    }
}

private fun insertAccount(accountData: AccountData) {
    val now = DateTime.now()
    val accountId = EntityID(accountData.username, Account)

    Account.insert {
        it[id] = accountId
        it[email] = accountData.email
        it[moodleUsername] = accountData.moodleUsername
        it[givenName] = accountData.givenName
        it[familyName] = accountData.familyName
        it[createdAt] = now
    }

    // Add implicit group for each account
    val groupId = Group.insertAndGetId {
        it[name] = accountData.username
        it[isImplicit] = true
        it[createdAt] = now
    }
    AccountGroup.insert {
        it[account] = accountId
        it[group] = groupId
        it[isManager] = false
        it[createdAt] = now
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


private fun updateStudentCourseAccesses(accountData: AccountData) {
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

            val groupIds = StudentPendingCourseGroup
                    .slice(StudentPendingCourseGroup.courseGroup)
                    .select { StudentPendingCourseGroup.pendingAccess eq pendingAccessId }
                    .map { it[StudentPendingCourseGroup.courseGroup] }

            StudentCourseGroup.batchInsert(groupIds) {
                this[StudentCourseGroup.student] = student.value
                this[StudentCourseGroup.course] = courseId.value
                this[StudentCourseGroup.courseGroup] = it
                this[StudentCourseGroup.courseAccess] = accessId
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

            val accessId = StudentCourseAccess.insertAndGetId {
                it[StudentCourseAccess.student] = student
                it[StudentCourseAccess.course] = EntityID(pendingAccess.courseId, Course)
            }

            val groupIds = StudentMoodlePendingCourseGroup
                    .slice(StudentMoodlePendingCourseGroup.courseGroup)
                    .select { StudentMoodlePendingCourseGroup.pendingAccess eq pendingAccess.id }
                    .map { it[StudentMoodlePendingCourseGroup.courseGroup] }

            StudentCourseGroup.batchInsert(groupIds) {
                this[StudentCourseGroup.student] = student.value
                this[StudentCourseGroup.course] = pendingAccess.courseId
                this[StudentCourseGroup.courseGroup] = it
                this[StudentCourseGroup.courseAccess] = accessId
            }
        }

        StudentMoodlePendingAccess.deleteWhere {
            StudentMoodlePendingAccess.id inList pendingAccesses.map { it.id }
        }
    }
}
