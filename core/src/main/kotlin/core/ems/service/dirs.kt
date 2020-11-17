package core.ems.service

import core.db.Dir
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

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