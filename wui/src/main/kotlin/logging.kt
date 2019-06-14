import kotlin.js.Date

private const val DEBUG_PREFIX = "[DEBUG]"
private const val WARN_PREFIX = "[WARN]"

private const val DEBUG_ENABLED = true
private const val WARN_ENABLED = true



fun debug(msgProvider: () -> String) {
    if (DEBUG_ENABLED)
        println("$DEBUG_PREFIX ${datetimeString()}: ${msgProvider()}")
}

fun warn(msgProvider: () -> String) {
    if (WARN_ENABLED)
        println("$WARN_PREFIX ${datetimeString()}: ${msgProvider()}")
}

private fun datetimeString(): String {
    return Date().toISOString().replace("T", " ").replace("Z", "")
}
