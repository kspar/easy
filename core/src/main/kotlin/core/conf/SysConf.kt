package core.conf

import core.db.SystemConfiguration
import core.db.insertOrUpdate
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object SysConf {

    // TODO: make it cachable and evict cache after certain amount of time
    fun getProp(key: String): String? {
        return transaction {
            SystemConfiguration.select {
                SystemConfiguration.id eq key
            }.map {
                it[SystemConfiguration.value]
            }.firstOrNull()
        }
    }

    fun putProp(key: String, value: String) {
        return transaction {
            SystemConfiguration.insertOrUpdate(SystemConfiguration.id, listOf(SystemConfiguration.id)) {
                it[SystemConfiguration.id] = org.jetbrains.exposed.dao.id.EntityID(key, SystemConfiguration)
                it[SystemConfiguration.value] = value
            }
        }
    }
}