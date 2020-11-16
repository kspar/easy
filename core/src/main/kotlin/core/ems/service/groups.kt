package core.ems.service

import core.db.AccountGroup
import core.db.Group
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

// TODO: cache
fun getAccountImplicitGroupId(accountId: String): EntityID<Long> {
    return transaction {
        (Group innerJoin AccountGroup).slice(Group.id)
                .select {
                    AccountGroup.account eq accountId and Group.isImplicit
                }.map { it[Group.id] }
                .single()
    }
}