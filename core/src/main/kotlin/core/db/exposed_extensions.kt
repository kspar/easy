package core.db

import org.jetbrains.exposed.v1.core.SortOrder

// Shortcut for finding the complement/negation of SortOrder
fun SortOrder.complement() = when (this) {
    SortOrder.ASC -> SortOrder.DESC
    SortOrder.DESC -> SortOrder.ASC
    SortOrder.ASC_NULLS_FIRST -> SortOrder.DESC_NULLS_FIRST
    SortOrder.DESC_NULLS_FIRST -> SortOrder.ASC_NULLS_FIRST
    SortOrder.ASC_NULLS_LAST -> SortOrder.DESC_NULLS_LAST
    SortOrder.DESC_NULLS_LAST -> SortOrder.ASC_NULLS_LAST
}
