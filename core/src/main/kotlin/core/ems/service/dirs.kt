package core.ems.service

import core.db.Dir
import core.db.DirAccessLevel
import core.db.Group
import core.db.GroupDirAccess
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

private val log = KotlinLogging.logger {}


fun assertDirExists(dirId: Long, allowImplicit: Boolean = false) {
    if (!dirExists(dirId, allowImplicit)) {
        val explicit = if (!allowImplicit) "explicit" else ""
        throw InvalidRequestException("No $explicit dir with id $dirId",
                ReqError.ENTITY_WITH_ID_NOT_FOUND, "id" to dirId.toString())
    }
}

fun dirExists(dirId: Long, allowImplicit: Boolean = false): Boolean {
    return transaction {
        val q = Dir.select {
            Dir.id eq dirId
        }
        if (!allowImplicit) {
            q.andWhere { Dir.isImplicit eq false }
        }

        q.count() == 1L
    }
}

@Deprecated("For debugging only")
fun debugPrintDir(dirId: Long? = null) {
    log.warn { "For debugging only" }
    printDir(dirId, 0)
}

private fun printDir(dirId: Long? = null, level: Int = 0) {
    transaction {
        Dir.select {
            Dir.parentDir eq dirId
        }.map {
            val id = it[Dir.id].value
            val name = it[Dir.name]
            val anyAccess = if (it[Dir.anyAccess] != null) "(${it[Dir.anyAccess]})" else ""
            val implicit = if (it[Dir.isImplicit]) "EXR" else "DIR"

            data class GroupAccess(val groupId: Long, val groupName: String, val access: DirAccessLevel)

            val accesses = (GroupDirAccess innerJoin Group)
                    .select {
                        GroupDirAccess.dir eq id
                    }.map {
                        GroupAccess(
                                it[Group.id].value,
                                it[Group.name],
                                it[GroupDirAccess.level]
                        )
                    }

            log.debug { "${" ".repeat(level * 4)}| $implicit: $name [$id] $anyAccess" }
            accesses.forEach {
                log.debug { "${" ".repeat(level * 4)}  Group ${it.groupName} [${it.groupId}] - ${it.access}" }
            }

            printDir(id, level + 1)
        }
    }
}
