package core.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.Function
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager


// Waiting for exposed upsert: https://github.com/JetBrains/Exposed/issues/167
fun <T : Table> T.insertOrUpdate(key: Column<*>, excluded: List<Column<*>>, body: T.(InsertStatement<Number>) -> Unit) =
        InsertOrUpdate<Number>(this, keys = listOf(key), excluded = excluded).apply {
            body(this)
            execute(TransactionManager.current())
        }

fun <T : Table> T.insertOrUpdate(keys: List<Column<*>>, excluded: List<Column<*>>, body: T.(InsertStatement<Number>) -> Unit) =
        InsertOrUpdate<Number>(this, keys = keys, excluded = excluded).apply {
            body(this)
            execute(TransactionManager.current())
        }

class InsertOrUpdate<Key : Any>(
        table: Table,
        isIgnore: Boolean = false,
        private val keys: List<Column<*>>,
        private val excluded: List<Column<*>>
) : InsertStatement<Key>(table, isIgnore) {
    override fun prepareSQL(transaction: Transaction): String {
        val tm = TransactionManager.current()
        val updateCols = table.columns.minus(excluded)
        val updateSetter = updateCols.joinToString { "${tm.identity(it)} = EXCLUDED.${tm.identity(it)}" }
        val keyColsStr = keys.joinToString { tm.identity(it) }
        val onConflict = "ON CONFLICT ($keyColsStr) DO UPDATE SET $updateSetter"
        return "${super.prepareSQL(transaction)} $onConflict"
    }
}


// Waiting for exposed DISTINCT ON. Extension inspired by https://github.com/JetBrains/Exposed/issues/500
fun <T> Column<T>.distinctOn() = DistinctOn(this)

class DistinctOn<T>(private val expr: Column<T>) : Function<T>(expr.columnType) {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder { append("DISTINCT ON (", expr, ") ", expr) }
    }
}


// Waiting for NULLS FIRST/LAST support (https://github.com/JetBrains/Exposed/issues/478)
// Workaround: use ORDER BY <col> IS NULL ASC to force nulls last

// IS NULL clause that can be used in ORDER BY
fun <T> Expression<T>.isNull() = IsNullExp(this)

class IsNullExp(val expr: Expression<*>) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append(expr, " IS NULL") }
}


// Shortcut for finding the complement/negation of SortOrder
fun SortOrder.complement() = when(this) {
    SortOrder.ASC -> SortOrder.DESC
    SortOrder.DESC -> SortOrder.ASC
}
