package components

import Icons
import debug
import hide
import kotlinx.coroutines.await
import libheaders.MToastInstance
import libheaders.Materialize
import org.w3c.dom.HTMLButtonElement
import rip.kspar.ezspa.*
import template

typealias ToastId = String

val activeToasts: MutableMap<ToastId, ToastThing> = mutableMapOf()

object ToastIds {
    val noCourseAccess = IdGenerator.nextId()
    val noVisibleCourseExercise = IdGenerator.nextId()
    val noPermissionForPage = IdGenerator.nextId()
    val loginToContinue = IdGenerator.nextId()
}

class ToastThing(
    private val text: String,
    private val action: Action? = null,
    private val icon: String = Icons.check,
    private val isDismissable: Boolean = true,
    private val displayLengthSec: Int = 5,
    val id: ToastId = IdGenerator.nextId(),
) {

    companion object {
        const val LONG_TIME = 60 * 60 * 24 * 365
    }

    data class Action(
        val label: String, val action: suspend () -> Unit,
        val dismissOnClick: Boolean = true, val hideAfterSec: Int? = null,
    )

    private var instance: MToastInstance?

    init {
        activeToasts[id]?.let {
            debug { "Dismissing toast '${it.text}' with same id $id" }
            it.dismiss()
        }

        debug { "Showing toast: $text" }
        activeToasts[id] = this

        val actionBtnId = IdGenerator.nextId()
        val dismissBtnId = IdGenerator.nextId()

        instance = Materialize.toast(
            objOf(
                "unsafeHTML" to createHtml(actionBtnId, dismissBtnId),
                // Dismiss manually to make sure instance is nulled after dismiss and to sync global toasts
                "displayLength" to 1_000_000_000_000,
            )
        )

        doInPromise {
            sleep(displayLengthSec * 1000).await()
            dismiss()
        }

        if (action != null) {
            getElemByIdAs<HTMLButtonElement>(actionBtnId).let { btn ->
                btn.onVanillaClick(true) {
                    if (action.dismissOnClick) {
                        btn.disabled = true
                        dismiss()
                    }
                    action.action()
                }
                if (action.hideAfterSec != null) {
                    doInPromise {
                        sleep(action.hideAfterSec * 1000).await()
                        hideAction(actionBtnId)
                    }
                }
            }
        }
        if (isDismissable) {
            getElemById(dismissBtnId).onVanillaClick(true) {
                dismiss()
            }
        }

    }

    fun dismiss() {
        instance?.dismiss()
        // Materialize bug: if dismiss is called more than once per instance then following toasts disappear instantly
        instance = null
        activeToasts.remove(id)
    }

    private fun hideAction(actionBtnId: String) {
        getElemByIdOrNull(actionBtnId)?.hide(true)
    }

    private fun createHtml(actionBtnId: String, dismissBtnId: String) = template(
        """
            <ez-toast-icon class='icon-med'>{{{icon}}}</ez-toast-icon>
            <span class="msg truncate">{{msg}}</span>
            
            {{#action}}
                <button id="$actionBtnId" class="btn-flat toast-action waves-effect waves-light" style='{{^dismissable}}margin-right: 0;{{/dismissable}}'>
                    <ez-btn-content>
                        <ez-btn-text>{{btnText}}</ez-btn-text>
                    </ez-btn-content>
                </button>
            {{/action}}
            
            {{#dismissable}}
                <button id="$dismissBtnId" class="btn-flat toast-action"><i class="material-icons dismiss">close</i></button>
            {{/dismissable}}
        """.trimIndent(),
        "icon" to icon,
        "msg" to text,
        "action" to (action != null),
        "btnText" to action?.label,
        "dismissable" to isDismissable,
    )

}