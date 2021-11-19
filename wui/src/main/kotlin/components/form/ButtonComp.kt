package components.form

import debug
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.asList
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemByIdAs
import rip.kspar.ezspa.onVanillaClick
import tmRender

class ButtonComp(
    private val type: Type,
    private val label: String,
    private val onClick: suspend (() -> Unit),
    private val disabledLabel: String? = null,
    private val postClick: (suspend (() -> Unit))? = null,
    parent: Component
) : Component(parent) {

    enum class Type {
        PRIMARY, SECONDARY
    }

    private val btnId = IdGenerator.nextId()

    private val element: HTMLButtonElement
        get() = getElemByIdAs(btnId)

    override fun render() = tmRender(
        "t-c-button",
        "id" to btnId,
        "text" to label,
        "isPrimary" to (type == Type.PRIMARY),
        "isSecondary" to (type == Type.SECONDARY),
    )

    override fun postRender() {
        element.onVanillaClick(true) {
            val btnContent = element.getElementsByTagName("ez-btn-content").asList().single()
            val activeHtml = btnContent.innerHTML
            disable()
            if (disabledLabel != null) {
                btnContent.textContent = disabledLabel
            }
            try {
                onClick()
            } finally {
                btnContent.innerHTML = activeHtml
                enable()
            }
            postClick?.invoke()
        }
    }

    fun disable() = setEnabled(false)

    fun enable() = setEnabled(true)

    fun setEnabled(isEnabled: Boolean) {
        debug { "Set button $label enabled: $isEnabled" }
        element.disabled = !isEnabled
    }
}