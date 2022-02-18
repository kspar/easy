package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.cache.CachingService
import core.ems.service.cache.countTotalUsersCache
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.SendMailService
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
class UpdateAccountController(val cachingService: CachingService, private val mailService: SendMailService) {

    data class PersonalDataBody(
        @JsonProperty("first_name", required = true)
        @field:NotBlank @field:Size(max = 100) val firstName: String,
        @JsonProperty("last_name", required = true)
        @field:NotBlank @field:Size(max = 100) val lastName: String
    )


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/account/checkin")
    fun controller(@Valid @RequestBody dto: PersonalDataBody, caller: EasyUser) {

        checkIdMigration(caller, dto)

        val account = AccountData(
            caller.id,
            caller.moodleUsername,
            caller.email.lowercase(),
            correctNameCapitalisation(dto.firstName),
            correctNameCapitalisation(dto.lastName)
        )

        log.debug { "Update personal data for $account" }
        val isChanged = updateAccount(account, cachingService)

        if (isChanged) {
            cachingService.invalidate(countTotalUsersCache)
            cachingService.evictAccountCache(account.username)
        }

        if (caller.isStudent()) {
            if (!cachingService.studentExists(account.username)) {
                log.debug { "Update student ${caller.id}" }
                updateStudent(account)
            }
            if (isChanged) {
                log.debug { "Update student course access ${caller.id}" }
                updateStudentCourseAccesses(account)
                cachingService.evictAccountCache(account.username)
            }
        }

        if (caller.isTeacher() && !cachingService.teacherExists(account.username)) {
            log.debug { "Update teacher ${caller.id}" }
            updateTeacher(account)
        }

        if (caller.isAdmin() && !cachingService.adminExists(account.username)) {
            log.debug { "Update admin ${caller.id}" }
            updateAdmin(account)
            // Admins should also have a teacher entity to add assessments, exercises etc
            updateTeacher(account)
        }

        updateLastSeen(caller.id)
    }

    private fun checkIdMigration(caller: EasyUser, dto: PersonalDataBody) {
        if (caller.oldId != caller.id) {
            log.debug { "Old id '${caller.oldId}' != new id '${caller.id}'" }

            // Possible cases:
            // only account with old id and migrated=false - migrate
            // only account with old id and migrated=true - error, notify
            // only account with new id and migrated=true - nothing
            // only account with new id and migrated=false - nothing
            // both accounts - error, notify
            // no accounts - nothing

            val oldAccount = cachingService.selectAccount(caller.oldId)
            val newAccount = cachingService.selectAccount(caller.id)

            if (oldAccount != null && newAccount != null) {
                val msg = "Accounts with both old and migrated ids exist. Old: $oldAccount, new: $newAccount"
                log.error { msg }
                mailService.sendSystemNotification(msg)
                throwMigrationFailed()
            }

            if (oldAccount != null) {
                if (oldAccount.isIdMigrated) {
                    val msg = "Non-migrated account with migrated=true: $oldAccount, newId: ${caller.id}"
                    log.error { msg }
                    mailService.sendSystemNotification(msg)
                    throwMigrationFailed()
                } else {
                    migrateAccountId(caller.oldId, caller.id)
                }
            }

            if (newAccount != null) {
                if (newAccount.isIdMigrated) {
                    log.info { "Account already migrated: $newAccount" }
                } else {
                    log.info { "Account not migrated: $newAccount" }
                }
            }
        }

        if (dto.firstName != caller.givenName ||
            dto.lastName != caller.familyName
        ) {
            val msg = """
               Account data mismatch from token and checkin:
                dto.firstName '${dto.firstName}' != caller.givenName '${caller.givenName}'
                dto.lastName '${dto.lastName}' != caller.familyName '${caller.familyName}'
            """
            log.warn { msg }
        }
    }

    private fun migrateAccountId(oldId: String, newId: String) {
        log.info { "Migrating account id '$oldId' -> '$newId'" }

        try {
            transaction {
                Account.update({ Account.id eq oldId }) {
                    it[id] = newId
                    it[idMigrationDone] = true
                    it[preMigrationId] = oldId
                }
            }
        } catch (e: Exception) {
            val msg = "Updating account id failed: $oldId -> $newId"
            log.error { msg }
            mailService.sendSystemNotification(msg)
            throwMigrationFailed()
        }

        cachingService.evictAccountCache(oldId)
        // Probs not necessary for new account since it was null before and wasn't cached, just precautionary
        cachingService.evictAccountCache(newId)
    }

    private fun throwMigrationFailed(): Nothing =
        throw InvalidRequestException("Account migration failed", ReqError.ACCOUNT_MIGRATION_FAILED, notify = false)

    private fun updateLastSeen(id: String) {
        // Not evicting cache, so lastSeen shouldn't be included in the cached query, or else it will be false
        transaction {
            Account.update({ Account.id eq id }) {
                it[lastSeen] = DateTime.now()
            }
        }
    }
}

data class AccountData(
    val username: String,
    val moodleUsername: String?,
    val email: String,
    val givenName: String,
    val familyName: String
)

private fun correctNameCapitalisation(name: String) =
    name.split(Regex(" +"))
        .joinToString(separator = " ") {
            it.split("-").joinToString(separator = "-") {
                it.lowercase().replaceFirstChar(Char::titlecase)
            }
        }

private fun updateAccount(accountData: AccountData, cachingService: CachingService): Boolean {
    return transaction {

        val oldAccount = cachingService.selectAccount(accountData.username)

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
        it[lastSeen] = now
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
    val now = DateTime.now()

    transaction {
        val courseIds = StudentPendingAccess
            .slice(StudentPendingAccess.course)
            .select { StudentPendingAccess.email eq accountData.email }
            .map { it[StudentPendingAccess.course].value }

        StudentPendingAccess.deleteWhere {
            StudentPendingAccess.email eq accountData.email
        }

        courseIds.forEach { courseId ->
            log.debug { "Granting access for ${accountData.username} to course $courseId" }

            // TODO: maybe can batchInsert
            StudentCourseAccess.insert {
                it[student] = accountData.username
                it[course] = courseId
                it[createdAt] = now
            }

            val groupIds = StudentPendingCourseGroup
                .slice(StudentPendingCourseGroup.courseGroup)
                .select {
                    StudentPendingCourseGroup.email.eq(accountData.email) and
                            StudentPendingCourseGroup.course.eq(courseId)
                }
                .map { it[StudentPendingCourseGroup.courseGroup] }

            StudentCourseGroup.batchInsert(groupIds) {
                this[StudentCourseGroup.student] = accountData.username
                this[StudentCourseGroup.course] = courseId
                this[StudentCourseGroup.courseGroup] = it
            }
        }

        data class MoodlePendingAccess(val courseId: Long, val moodleUsername: String)

        val moodlePendingQuery = StudentMoodlePendingAccess
            .select { StudentMoodlePendingAccess.email eq accountData.email }

        if (accountData.moodleUsername != null) {
            moodlePendingQuery.orWhere {
                StudentMoodlePendingAccess.moodleUsername eq accountData.moodleUsername
            }
        }

        val pendingAccesses = moodlePendingQuery
            .map {
                MoodlePendingAccess(
                    it[StudentMoodlePendingAccess.course].value,
                    it[StudentMoodlePendingAccess.moodleUsername]
                )
            }

        pendingAccesses.forEach { pendingAccess ->
            log.debug { "Granting access for ${accountData.username} to course ${pendingAccess.courseId} based on Moodle pending access $pendingAccess" }

            // Update moodleUsername for every pending access - not ideal but unlikely to cause problems
            Account.update({ Account.id eq accountData.username }) {
                it[moodleUsername] = pendingAccess.moodleUsername
            }

            // TODO: maybe can batchInsert
            StudentCourseAccess.insert {
                it[student] = accountData.username
                it[course] = pendingAccess.courseId
                it[createdAt] = now
            }

            val groupIds = StudentMoodlePendingCourseGroup
                .slice(StudentMoodlePendingCourseGroup.courseGroup)
                .select {
                    StudentMoodlePendingCourseGroup.moodleUsername.eq(pendingAccess.moodleUsername) and
                            StudentMoodlePendingCourseGroup.course.eq(pendingAccess.courseId)
                }
                .map { it[StudentMoodlePendingCourseGroup.courseGroup] }

            StudentCourseGroup.batchInsert(groupIds) {
                this[StudentCourseGroup.student] = accountData.username
                this[StudentCourseGroup.course] = pendingAccess.courseId
                this[StudentCourseGroup.courseGroup] = it
            }

            StudentMoodlePendingAccess.deleteWhere {
                StudentMoodlePendingAccess.course.eq(pendingAccess.courseId) and
                        StudentMoodlePendingAccess.moodleUsername.eq(pendingAccess.moodleUsername)
            }
        }
    }
}
