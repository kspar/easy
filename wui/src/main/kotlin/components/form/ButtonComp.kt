package components.form

import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.asList
import rip.kspar.ezspa.*
import tmRender

class ButtonComp(
    private val type: Type,
    // TODO: label can be empty
    private val label: String,
    private val iconHtml: String? = null,
    private val onClick: suspend (() -> Unit),
    private val isEnabledInitial: Boolean = true,
    private val disabledLabel: String? = null,
    private val postClick: (suspend (() -> Unit))? = null,
    parent: Component
) : Component(parent) {

    enum class Type {
        PRIMARY, FLAT, DANGER, PRIMARY_ROUND,
    }

    private val btnId = IdGenerator.nextId()

    private val element: HTMLButtonElement
        get() = getElemByIdAs(btnId)

    override fun render() = tmRender(
        "t-c-button",
        "id" to btnId,
        "text" to label,
        "iconHtml" to iconHtml,
        "isPrimary" to (type == Type.PRIMARY),
        "isSecondary" to (type == Type.FLAT),
        "isDanger" to (type == Type.DANGER),
        "isPrimaryRound" to (type == Type.PRIMARY_ROUND),
        "isDisabled" to !isEnabledInitial,
    )

    override fun postRender() {
        element.onVanillaClick(true) {
            val btnText = element.getElementsByTagName("ez-btn-text").asList().single()
            val activeHtml = btnText.innerHTML
            disable()

            if (disabledLabel != null) {
                btnText.textContent = disabledLabel
            }
            try {
                onClick()
            } finally {
                // element might've been destroyed on click
                if (getElemByIdOrNull(btnId) != null) {
                    btnText.innerHTML = activeHtml
                    enable()
                }
            }
            postClick?.invoke()
        }
    }

    fun disable() = setEnabled(false)

    fun enable() = setEnabled(true)

    fun setEnabled(isEnabled: Boolean) {
        element.let {
            it.disabled = !isEnabled
            if (isEnabled) it.removeClass("disabled")
            else it.addClass("disabled")
        }
    }

    fun click() = element.click()
}