package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.Account
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update


/**
 * The account with this email address, or null. Case-insensitive.
 *
 * `account.email` is a plain `text` column — no `citext`, no unique constraint and no index — so a
 * raw `eq` is case-sensitive, and an address typed with any capital letter could never match a row,
 * however real the account. EZ-1863: a teacher typed `Janjparve@…` into "add teachers" and was told
 * three times that no such user exists.
 *
 * **Both sides are lowered, not just the argument.** Writes have gone through
 * `caller.email.lowercase()` since `67913654` — which is *this same bug*, fixed on the write side in
 * 2019 and titled "adding students to course by email is case-insensitive" — so lowering the input
 * alone would be enough for every row written since. It would also make any row predating that
 * commit, or written around it, unreachable by *any* input, where today it is at least findable by
 * typing its stored spelling exactly. Lowering the column costs nothing here (there is no index for
 * it to defeat) and needs no data migration to be true, which is the better trade for a lookup that
 * runs a handful of times per request at most.
 *
 * That 2019 commit lowered a column the same way, in the endpoint that has since been deleted. This
 * is the surviving half of the same idea.
 */
fun getUsernameByEmail(email: String): String? = transaction {
    Account
        .select(Account.id)
        .where { Account.email.lowerCase() eq normaliseEmail(email) }
        .map { it[Account.id].value }
        .singleOrNull()
}

/**
 * An email address as the columns holding one are written: `account.email` by `account_checkin.kt`,
 * `student_moodle_pending_access.email` by the Moodle sync. `trim` as well as `lowercase`, because
 * the addresses reaching these lookups are pasted by hand from spreadsheets and mail clients.
 */
fun normaliseEmail(email: String): String = email.trim().lowercase()

fun teacherExists(username: String): Boolean = transaction {
    Account.selectAll().where { Account.id eq username and Account.isTeacher }.count() > 0
}

data class TeacherResp(
    @get:JsonProperty("id")
    val id: String,
    @get:JsonProperty("given_name")
    val givenName: String,
    @get:JsonProperty("family_name")
    val familyName: String
)


fun selectTeacher(teacherId: String) = transaction {
    Account
        .select(Account.id, Account.givenName, Account.familyName)
        .where { Account.id eq teacherId and Account.isTeacher }
        .map { TeacherResp(it[Account.id].value, it[Account.givenName], it[Account.familyName]) }
        .singleOrInvalidRequest()
}

fun selectPseudonym(username: String): String = transaction {
    Account
        .select(Account.pseudonym)
        .where { Account.id eq username }
        .map { it[Account.pseudonym] }
        .single()
}

fun insertTeacher(teacherId: String) {
    transaction {
        Account.update({ Account.id eq teacherId }) {
            it[Account.isTeacher] = true
        }
    }
}


