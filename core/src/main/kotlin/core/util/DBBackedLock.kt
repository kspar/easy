package core.util

import core.exception.ResourceLockedException
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

/**
 * Simple thread-safe lock backed by a DB column (field).
 * The backing table must have an ID column and a boolean column that's used for storing the lock state.
 * The lock is associated with a specific row (entity) in the table and doesn't affect other rows.
 *
 * The lock state can be read directly from the DB field, but it's best not to modify it manually.
 * In the lock column, true means that the lock is taken and false means that it's available.
 */
class DBBackedLock<IdType : Comparable<IdType>>(
    private val backingTable: IdTable<IdType>,
    private val backingLockColumn: Column<Boolean>,
) {

    // Monitor used for reading and modifying the backing DB lock column.
    // This monitor is global, meaning that it's shared for entities (rows).
    // This isn't ideal but also not a huge problem since the get/set lock by entity ID operation is fast.
    private val monitor = UUID.randomUUID()

    /**
     * Try to obtain the lock, do something while holding it (block()) and release the lock when finished.
     *
     * @throws ResourceLockedException if the lock is already taken
     */
    fun <R> with(entityId: IdType, block: () -> R): R {
        if (!tryObtain(entityId)) {
            throw ResourceLockedException()
        }
        try {
            return block()
        } finally {
            release(entityId)
        }
    }

    /**
     * Release locks for all rows (entities).
     * Should only be called on special occasions e.g. application startup or shutdown.
     */
    fun releaseAll() {
        // sync doesn't seem necessary, just precautionary
        synchronized(monitor) {
            transaction {
                backingTable.update {
                    it[backingLockColumn] = false
                }
            }
        }
    }

    private fun tryObtain(entityId: IdType): Boolean {
        return synchronized(monitor) {
            transaction {
                val isInProgress = backingTable.select {
                    backingTable.id eq entityId
                }.map {
                    it[backingLockColumn]
                }.single()

                if (isInProgress) {
                    false
                } else {
                    backingTable.update({
                        backingTable.id eq entityId
                    }) {
                        it[backingLockColumn] = true
                    }
                    true
                }
            }
        }
    }

    private fun release(entityId: IdType) {
        // sync doesn't seem necessary, just precautionary
        synchronized(monitor) {
            transaction {
                backingTable.update({
                    backingTable.id eq entityId
                }) {
                    it[backingLockColumn] = false
                }
            }
        }
    }
}