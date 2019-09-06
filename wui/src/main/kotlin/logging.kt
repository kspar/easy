import libheaders.Materialize
import kotlin.js.Date

private const val DEBUG_PREFIX = "[DEBUG]"
private const val WARN_PREFIX = "[WARN]"


// No console.debug in kotlin/js...
fun debug(msgProvider: () -> String?) {
    if (AppProperties.LOG_DEBUG_ENABLED)
        console.log("$DEBUG_PREFIX ${datetimeString()}: ${msgProvider()}")
}

fun warn(msgProvider: () -> String?) {
    if (AppProperties.LOG_WARN_ENABLED)
        console.warn("$WARN_PREFIX ${datetimeString()}: ${msgProvider()}")
}


class FunLog(private val funName: String, private val funStartTime: Double) {
    fun end() {
        debug { "<-- ${this.funName} (took ${Date.now() - this.funStartTime} ms)" }
    }
}

fun debugFunStart(funName: String): FunLog? {
    if (AppProperties.LOG_DEBUG_ENABLED) {
        debug { "--> $funName" }
        return FunLog(funName, Date.now())
    }
    return null
}

fun errorMessage(msgProvider: () -> String) {
    val msg = msgProvider()
    debug { "Showing error message: $msg" }
    val toastHtml = tmRender("tm-error-message", mapOf(
            "error" to msg,
            "dismiss" to Str.errorDismiss()
    ))
    Materialize.toast(objOf(
            "html" to toastHtml,
            "displayLength" to 15_000
    ))
}


private fun datetimeString(): String {
    return Date().toISOString().replace("T", " ").replace("Z", "")
}
