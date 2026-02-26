package core.db

import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.Function


// Waiting for exposed DISTINCT ON. Extension inspired by https://github.com/JetBrains/Exposed/issues/500
class DistinctOn<T>(columns: List<Column<*>>) : Function<T>(columns.first().columnType as IColumnType<T & Any>) {
    private val distinctNames = columns.joinToString(", ") {
        "${it.table.tableName}.${it.name}"
    }

    private val colName = columns.first().table.tableName + "." + columns.first().name

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder {
            append(" DISTINCT ON ($distinctNames) $colName ")
        }
    }
}

// Shortcut for finding the complement/negation of SortOrder
fun SortOrder.complement() = when (this) {
    SortOrder.ASC -> SortOrder.DESC
    SortOrder.DESC -> SortOrder.ASC
    SortOrder.ASC_NULLS_FIRST -> SortOrder.DESC_NULLS_FIRST
    SortOrder.DESC_NULLS_FIRST -> SortOrder.ASC_NULLS_FIRST
    SortOrder.ASC_NULLS_LAST -> SortOrder.DESC_NULLS_LAST
    SortOrder.DESC_NULLS_LAST -> SortOrder.ASC_NULLS_LAST
}
