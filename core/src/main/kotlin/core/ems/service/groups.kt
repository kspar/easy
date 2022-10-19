package core.ems.service

import core.db.Account
import core.db.AccountGroup
import core.db.Group
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

fun getImplicitGroupFromAccount(accountId: String): Long = transaction {
    Group
        .slice(Group.id)
        .select {
            Group.name.eq(accountId) and Group.isImplicit
        }
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
        .slice(
            Account.id, Account.givenName, Account.familyName, Account.email,
            Account.moodleUsername, Account.createdAt, Account.lastSeen
        ).select {
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
