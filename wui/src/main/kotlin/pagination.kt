
data class PaginationConf(val pageStart: Int, val pageEnd: Int, val pageTotal: Int, val canGoBack: Boolean, val canGoForward: Boolean)

fun getLastPageOffset(totalCount: Int, step: Int): Int {
    val itemsOnLastPageRaw = totalCount % step
    val itemsOnLastPage = when {
        itemsOnLastPageRaw == 0 && totalCount == 0 -> 0
        itemsOnLastPageRaw == 0 -> step
        else -> itemsOnLastPageRaw
    }
    return totalCount - itemsOnLastPage
}
