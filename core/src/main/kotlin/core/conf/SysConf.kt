package core.conf

import core.db.SystemConfiguration
import core.db.insertOrUpdate
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object SysConf {

    // TODO: make it cacheable and evict cache after certain amount of time
    fun getProp(key: String): String? = transaction {
        SystemConfiguration.selectAll().where { SystemConfiguration.id eq key }.map {
            it[SystemConfiguration.value]
        }.firstOrNull()
    }

    fun putProp(key: String, value: String) = transaction {
        SystemConfiguration.insertOrUpdate(SystemConfiguration.id, listOf(SystemConfiguration.id)) {
            it[SystemConfiguration.id] = key
            it[SystemConfiguration.value] = value
        }
    }
}