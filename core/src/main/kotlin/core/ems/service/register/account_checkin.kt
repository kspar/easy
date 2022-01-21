package core.ems.service.register

import com.fasterxml.jackson.annotation.JsonProperty
import core.ems.service.cache.countTotalUsersCache
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.cache.CachingService
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
class UpdateAccountController(val cachingService: CachingService) {

    data class PersonalDataBody(
        @JsonProperty("first_name", required = true)
        @field:NotBlank @field:Size(max = 100) val firstName: String,
        @JsonProperty("last_name", required = true)
        @field:NotBlank @field:Size(max = 100) val lastName: String
    )


    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/account/checkin")
    fun controller(@Valid @RequestBody dto: PersonalDataBody, caller: EasyUser) {

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
