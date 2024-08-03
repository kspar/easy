package components

import libheaders.MdButton
import rip.kspar.ezspa.*
import template

class ButtonComp(
    private val type: Type,
    var label: String,
    var icon: String? = null,
    private val trailingIcon: Boolean = false,
    var enabled: Boolean = true,
    private val onClick: suspend (() -> Unit),
    private val disableOnClick: Boolean = false,
    private val btnId: String = IdGenerator.nextId(),
    parent: Component
) : Component(parent) {

    private val btnInstance
        get() = getElemById(btnId).MdButton()

    enum class Type {
        FILLED, OUTLINED, ELEVATED, TONAL, TEXT
    }

    override fun render() = template(
        """
            <{{#filled}}md-filled-button{{/filled}}{{#outlined}}md-outlined-button{{/outlined}}{{#elevated}}md-elevated-button{{/elevated}}{{#tonal}}md-filled-tonal-button{{/tonal}}{{#text}}md-text-button{{/text}} {{#trailingIcon}}trailing-icon{{/trailingIcon}} {{#disabled}}disabled{{/disabled}} id="{{id}}">
            {{label}}
            {{#icon}}<md-icon slot='icon'>{{{icon}}}</md-icon>{{/icon}}
            </{{#filled}}md-filled-button{{/filled}}{{#outlined}}md-outlined-button{{/outlined}}{{#elevated}}md-elevated-button{{/elevated}}{{#tonal}}md-filled-tonal-button{{/tonal}}{{#text}}md-text-button{{/text}}>
        """.trimIndent(),
        "filled" to (type == Type.FILLED),
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

            onClick()

            if (disableOnClick && existsElemById(btnId))
                setEnabled(true)
        }
    }

    fun setEnabled(nowEnabled: Boolean) {
        enabled = nowEnabled
        btnInstance.disabled = !nowEnabled
    }
}