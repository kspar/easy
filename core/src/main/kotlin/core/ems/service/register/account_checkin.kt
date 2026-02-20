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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*
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

        val account = AccountData(
            caller.id,
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
                    oldAccount.familyName != accountData.familyName

            if (isChanged) {
                Account.update({ Account.id eq accountData.username }) {
                    it[email] = accountData.email
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
        it[givenName] = accountData.givenName
        it[familyName] = accountData.familyName
        it[createdAt] = now
        it[lastSeen] = now
        it[pseudonym] = UUID.randomUUID().toString().replace("-", "")
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
        Account.update({ Account.id eq student.username }) {
            it[isStudent] = true
        }
    }
}

private fun updateTeacher(teacher: AccountData) {
    transaction {
        Account.update({ Account.id eq teacher.username }) {
            it[isTeacher] = true
        }
    }
}

private fun updateAdmin(admin: AccountData) {
    transaction {
        Account.update({ Account.id eq admin.username }) {
            it[isAdmin] = true
        }
    }
}
