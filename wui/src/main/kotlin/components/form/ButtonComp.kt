package components.form

import hide
import libheaders.MdButton
import org.w3c.dom.HTMLButtonElement
import rip.kspar.ezspa.*
import show
import template

class ButtonComp(
    private val type: Type,
    var label: String,
    var icon: String? = null,
    private val clickedLabel: String? = null,
    private val trailingIcon: Boolean = false,
    var enabled: Boolean = true,
    private val onClick: suspend (() -> Unit),
    private val onPostClick: (suspend (() -> Unit))? = null,
    private val showLoading: Boolean = true,
    private val disableOnClick: Boolean = true,
    private val btnId: String = IdGenerator.nextId(),
    parent: Component
) : Component(parent) {

    private val btnInstance
        get() = getElemById(btnId).MdButton()

    enum class Type {
        FILLED, OUTLINED, ELEVATED, TONAL, TEXT,
        FILLED_DANGER
    }

    override fun render() = template(
        """
            <{{#filled}}md-filled-button{{/filled}}{{#outlined}}md-outlined-button{{/outlined}}{{#elevated}}md-elevated-button{{/elevated}}{{#tonal}}md-filled-tonal-button{{/tonal}}{{#text}}md-text-button{{/text}} {{#trailingIcon}}trailing-icon{{/trailingIcon}} {{#disabled}}disabled{{/disabled}} id="{{id}}" class='{{#danger}}danger{{/danger}}'>
                <md-circular-progress indeterminate class='display-none'></md-circular-progress>
                {{#icon}}<md-icon slot='icon'>{{{icon}}}</md-icon>{{/icon}}
                <ez-button-text>{{label}}</ez-button-text>
            </{{#filled}}md-filled-button{{/filled}}{{#outlined}}md-outlined-button{{/outlined}}{{#elevated}}md-elevated-button{{/elevated}}{{#tonal}}md-filled-tonal-button{{/tonal}}{{#text}}md-text-button{{/text}}>
        """.trimIndent(),
        "filled" to (type == Type.FILLED || type == Type.FILLED_DANGER),
        "danger" to (type == Type.FILLED_DANGER),
        "outlined" to (type == Type.OUTLINED),
        "elevated" to (type == Type.ELEVATED),
        "tonal" to (type == Type.TONAL),
        "text" to (type == Type.TEXT),
        "trailingIcon" to trailingIcon,
        "disabled" to !enabled,
        "id" to btnId,
        "label" to label,
        "icon" to icon,
    )

    override fun postRender() {
        getElemById(btnId).onVanillaClick(false) {
            if (disableOnClick)
                setEnabled(false)
            if (showLoading)
                setLoading(true)

            try {
                onClick()
            } finally {
                if (existsElemById(btnId)) {
                    if (disableOnClick)
                        setEnabled(true)
                    if (showLoading)
                        setLoading(false)
                }
            }

            onPostClick?.invoke()
        }
    }

    fun setEnabled(nowEnabled: Boolean) {
        enabled = nowEnabled
        btnInstance.disabled = !nowEnabled
    }

    fun click() = (getElemById(btnId).shadowRoot?.querySelector("button") as? HTMLButtonElement)?.click()

    private fun setLoading(loading: Boolean) {
        if (loading) {
            clickedLabel?.let {
                rootElement.getElemBySelector("ez-button-text").textContent = clickedLabel
            }
            // Spinners only implemented for FILLED
            if (type == Type.FILLED || type == Type.FILLED_DANGER) {
                rootElement.getElemBySelectorOrNull("md-icon")?.hide()
                rootElement.getElemBySelector("md-circular-progress").show()
            }
        } else {
            rootElement.getElemBySelector("ez-button-text").textContent = label
            if (type == Type.FILLED || type == Type.FILLED_DANGER) {
                rootElement.getElemBySelector("md-circular-progress").hide()
                rootElement.getElemBySelectorOrNull("md-icon")?.show()
            }
        }
    }
}