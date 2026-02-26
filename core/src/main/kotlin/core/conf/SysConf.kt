package core.conf

import core.db.SystemConfiguration
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

object SysConf {

    // TODO: make it cacheable and evict cache after certain amount of time
    fun getProp(key: String): String? = transaction {
        SystemConfiguration.selectAll().where { SystemConfiguration.id eq key }.map {
            it[SystemConfiguration.value]
        }.firstOrNull()
    }


    fun putProp(key: String, value: String) = transaction {
        SystemConfiguration.upsert(SystemConfiguration.id) {
            it[SystemConfiguration.id] = key
            it[SystemConfiguration.value] = value
        }
    }
}