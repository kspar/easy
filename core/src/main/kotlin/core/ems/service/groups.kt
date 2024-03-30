package core.ems.service

import core.conf.security.EasyUser
import core.db.Account
import core.db.AccountGroup
import core.db.Group
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime


fun hasUserGroupAccess(user: EasyUser, groupId: Long, requireManager: Boolean): Boolean {
    return when {
        user.isAdmin() -> true
        else -> hasAccountGroupAccess(user.id, groupId, requireManager)
    }
}

private fun hasAccountGroupAccess(accountId: String, groupId: Long, requireManager: Boolean): Boolean {
    return transaction {
        val q = AccountGroup.selectAll().where { AccountGroup.account eq accountId and (AccountGroup.group eq groupId) }
        if (requireManager) {
            q.andWhere { AccountGroup.isManager eq true }
        }
        q.count() >= 1L
    }
}

fun getImplicitGroupFromAccount(accountId: String): Long = transaction {
    Group
        .select(Group.id)
        .where { Group.name.eq(accountId) and Group.isImplicit }
        .map {
            it[Group.id]
        }
        .single().value
}

data class AccountFromImplicitGroup(
    val id: String, val givenName: String, val familyName: String, val email: String,
    val moodleUsername: String?, val createdAt: DateTime, val lastSeen: DateTime,
)

fun getAccountFromImplicitGroup(implicitGroupId: Long): AccountFromImplicitGroup = transaction {
    (Group innerJoin AccountGroup innerJoin Account)
        .select(
            Account.id, Account.givenName, Account.familyName, Account.email,
            Account.moodleUsername, Account.createdAt, Account.lastSeen
        ).where {
            Group.id eq implicitGroupId and Group.isImplicit
        }.map {
            AccountFromImplicitGroup(
                it[Account.id].value,
                it[Account.givenName],
                it[Account.familyName],
                it[Account.email],
                it[Account.moodleUsername],
                it[Account.createdAt],
                it[Account.lastSeen],
            )
        }.single()
}
