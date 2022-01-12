package core.util

// Destructure nullables
operator fun <T> Pair<T, *>?.component1() = this?.component1()
operator fun <T> Pair<*, T>?.component2() = this?.component2()


/**
 * Return the largest non-null element or null if there are no elements or if they're all null.
 */
fun <T : Comparable<T>> maxOfOrNull(vararg values: T?): T? {
    return values.filterNotNull().maxOrNull()
}
