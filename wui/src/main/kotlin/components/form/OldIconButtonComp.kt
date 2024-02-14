package components.form

import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import org.w3c.dom.HTMLElement
import rip.kspar.ezspa.*
import template

class OldIconButtonComp(
    private val iconHtml: String,
    private val label: String?,
    private val onClick: suspend (() -> Unit),
    private val isEnabledInitial: Boolean = true,
    private val showLoading: Boolean = true,
    parent: Component
) : Component(parent) {

    private val id = IdGenerator.nextId()

    private val element: HTMLElement
        get() = getElemByIdAs(id)

    private var isEnabled = isEnabledInitial

    // TODO: spinner
    override fun render() = template(
        """
            <ez-icon-action id='{{id}}' title="{{label}}" class="waves-effect {{#disabled}}disabled{{/disabled}}" tabindex="0">{{{icon}}}</ez-icon-action>
        """.trimIndent(),
        "id" to id,
        "label" to label,
        "icon" to iconHtml,
        "disabled" to !isEnabled,
    )

    override fun postRender() {
        element.onVanillaClick(true) {
            it.stopPropagation()

            if (!isEnabled)
                return@onVanillaClick

            disable()

//            if (showClickedLoading) {
//                icon?.hide()
//                loader.show()
//            }

            try {
                onClick()
            } finally {
                // element might've been destroyed on click
                if (getElemByIdOrNull(id) != null) {
//                    loader.hide()
//                    icon?.show()
                    enable()
                }
            }
        }
    }

    fun disable() = setEnabled(false)

    fun enable() = setEnabled(true)

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        element.let {
            if (enabled) it.removeClass("disabled")
            else it.addClass("disabled")
        }
    }

    fun click() = element.click()
}