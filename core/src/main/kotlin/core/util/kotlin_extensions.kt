package core.util

// Destructure nullables
operator fun <T> Pair<T, *>?.component1() = this?.component1()
operator fun <T> Pair<*, T>?.component2() = this?.component2()
