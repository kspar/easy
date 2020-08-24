import libheaders.Materialize
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import kotlin.js.Date
import kotlin.random.Random

private const val DEBUG_PREFIX = "[DEBUG]"
private const val WARN_PREFIX = "[WARN]"


// No console.debug in kotlin/js...
fun debug(msgProvider: () -> Any?) {
    if (AppProperties.LOG_DEBUG_ENABLED)
        console.log("$DEBUG_PREFIX ${datetimeString()}: ${msgProvider()}")
}

fun warn(msgProvider: () -> Any?) {
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


fun errorMessage(msgProvider: () -> String) = userMessage(msgProvider, MsgType.ERROR)

fun successMessage(msgProvider: () -> String) = userMessage(msgProvider, MsgType.SUCCESS)

fun userMessage(msgProvider: () -> String, type: MsgType) {
    val msg = msgProvider()
    debug { "Showing ${type.name} message: $msg" }
    val btnId = "toast${Random.Default.nextInt()}"
    val toastHtml = tmRender("tm-message", mapOf(
            "btnId" to btnId,
            "msg" to msg,
            "icon" to type.iconId
    ))
    val toast = Materialize.toast(objOf(
            "html" to toastHtml,
            "displayLength" to type.visibleTimeMs
    ))
    getElemById(btnId).onVanillaClick(true) {
        toast.dismiss()
    }
}

enum class MsgType(val iconId: String, val visibleTimeMs: Int) {
    ERROR("error_outline", 15_000),
    SUCCESS("check", 5_000)
}


private fun datetimeString(): String {
    return Date().toISOString().replace("T", " ").replace("Z", "")
}
