package core.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager


// Waiting for exposed upsert: https://github.com/JetBrains/Exposed/issues/167
fun <T : Table> T.insertOrUpdate(key: Column<*>, excluded: List<Column<*>>, body: T.(InsertStatement<Number>) -> Unit) =
        InsertOrUpdate<Number>(this, key = key, excluded = excluded).apply {
            body(this)
            execute(TransactionManager.current())
        }

class InsertOrUpdate<Key : Any>(
        table: Table,
        isIgnore: Boolean = false,
        private val key: Column<*>,
        private val excluded: List<Column<*>>
) : InsertStatement<Key>(table, isIgnore) {
    override fun prepareSQL(transaction: Transaction): String {
        val tm = TransactionManager.current()
        val updateCols = table.columns.minus(excluded)
        val updateSetter = updateCols.joinToString { "${tm.identity(it)} = EXCLUDED.${tm.identity(it)}" }
        val onConflict = "ON CONFLICT (${tm.identity(key)}) DO UPDATE SET $updateSetter"
        return "${super.prepareSQL(transaction)} $onConflict"
    }
}