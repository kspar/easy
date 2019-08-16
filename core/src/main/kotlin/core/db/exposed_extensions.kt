package core.db

import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IdTable
import org.jetbrains.exposed.sql.*
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

/**
 * infix method derived from Kotlin inList. It is inList analogue, except this method controls similarity (~ / !~), not equality.
 */
infix fun <T> ExpressionWithColumnType<T>.containsInList(list: Iterable<T>): Op<Boolean> = ContainsListOrNotInListOp(this, list, containsInList = true)

/**
 * Derived from Kotlin inList for IDs, controls similarity (~ / !~), not equality.
 */
@Suppress("UNCHECKED_CAST")
@JvmName("inListIds")
infix fun <T : Comparable<T>> Column<EntityID<T>>.containsInList(list: Iterable<T>): Op<Boolean> {
    val idTable = (columnType as EntityIDColumnType<T>).idColumn.table as IdTable<T>
    return containsInList(list.map { EntityID(it, idTable) })
}

class ContainsListOrNotInListOp<T>(val expr: ExpressionWithColumnType<T>, val list: Iterable<T>, val containsInList: Boolean = true) : Op<Boolean>() {
    override fun toSQL(queryBuilder: QueryBuilder): String = buildString {
        list.iterator().let { i ->
            if (!i.hasNext()) {
                val expr = Op.build { booleanLiteral(!containsInList) eq booleanLiteral(true) }
                append(expr.toSQL(queryBuilder))
            } else {
                val first = i.next()
                if (!i.hasNext()) {
                    append(expr.toSQL(queryBuilder))
                    when {
                        containsInList -> append(" ~* ")
                        else -> append(" !~* ")
                    }
                    append(queryBuilder.registerArgument(expr.columnType, first))
                } else {
                    append(expr.toSQL(queryBuilder))
                    when {
                        containsInList -> append(" IN (")
                        else -> append(" NOT IN (")
                    }

                    queryBuilder.registerArguments(expr.columnType, list).joinTo(this)

                    append(")")
                }
            }
        }
    }
}