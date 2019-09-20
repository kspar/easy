package core.conf

import core.db.SystemConfiguration
import core.db.insertOrUpdate
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object SysConf {

    fun getProp(key: String): String? {
        return transaction {
            SystemConfiguration.select {
                SystemConfiguration.key eq key
            }.map {
                it[SystemConfiguration.value]
            }.firstOrNull()
        }
    }

    fun putProp(key: String, value: String) {
        return transaction {
            SystemConfiguration.insertOrUpdate(SystemConfiguration.key, listOf(SystemConfiguration.key)) {
                it[SystemConfiguration.key] = key
                it[SystemConfiguration.value] = value
            }
        }
    }
}