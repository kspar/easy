import libheaders.Materialize
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import kotlin.js.Date

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


data class UserMessageAction(val label: String, val id: String = IdGenerator.nextId(), val onActivate: () -> Unit)

fun permanentErrorMessage(isDismissable: Boolean = true, action: UserMessageAction? = null, msgProvider: () -> String) =
    userMessage(msgProvider, MsgType.PERMANENT_ERROR, action, isDismissable)

fun errorMessage(isDismissable: Boolean = true, action: UserMessageAction? = null, msgProvider: () -> String) =
    userMessage(msgProvider, MsgType.ERROR, action, isDismissable)

fun successMessage(isDismissable: Boolean = true, action: UserMessageAction? = null, msgProvider: () -> String) =
    userMessage(msgProvider, MsgType.SUCCESS, action, isDismissable)

fun userMessage(msgProvider: () -> String, type: MsgType, action: UserMessageAction?, isDismissable: Boolean) {
    val msg = msgProvider()
    debug { "Showing ${type.name} message: $msg" }
    val dismissBtnId = IdGenerator.nextId()
    val toastHtml = tmRender(
        "tm-message", mapOf(
            "icon" to type.iconId,
            "msg" to msg,
            "action" to action?.let {
                mapOf(
                    "btnId" to it.id,
                    "label" to it.label,
                )
            },
            "dismissable" to isDismissable,
            "dismissBtnId" to dismissBtnId,
        )
    )
    val toast = Materialize.toast(
        objOf(
            "unsafeHTML" to toastHtml,
            "displayLength" to type.visibleTimeMs
        )
    )
    if (action != null) {
        getElemById(action.id).onVanillaClick(true) {
            action.onActivate.invoke()
        }
    }
    if (isDismissable) {
        getElemById(dismissBtnId).onVanillaClick(true) {
            toast.dismiss()
        }
    }
}

enum class MsgType(val iconId: String, val visibleTimeMs: Int) {
    ERROR("error_outline", 15_000),
    PERMANENT_ERROR("error_outline", 31_556_952),
    SUCCESS("check", 5_000)
}


private fun datetimeString(): String {
    return Date().toISOString().replace("T", " ").replace("Z", "")
}
