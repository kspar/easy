package components

import libheaders.MdIconBtn
import rip.kspar.ezspa.*
import template

class IconButtonComp(
    var icon: String,
    var label: String?,
    // if set, this btn will act as a toggle btn
    val toggle: Toggle? = null,
    var enabled: Boolean = true,
    val size: Size = Size.NORMAL,
    private val onClick: suspend (() -> Unit),
    private val disableOnClick: Boolean = false,
    private val stopPropagation: Boolean = false,
    private val btnId: String = IdGenerator.nextId(),
    parent: Component
) : Component(parent) {

    enum class Size { SMALL, NORMAL }

    data class Toggle(
        val toggledIcon: String,
        val startToggled: Boolean = false,
    )

    private val btnInstance
        get() = getElemById(btnId).MdIconBtn()

    var toggled: Boolean = false
        set(value) {
            field = value
            btnInstance.selected = value
        }

    override fun render() = template(
        """
            <md-icon-button id="{{id}}" title="{{label}}" {{#toggle}}toggle{{/toggle}} {{^enabled}}disabled{{/enabled}} class='{{#small}}small{{/small}}'>
                <md-icon>{{{icon}}}</md-icon>
                <md-icon slot="selected">{{{toggledIcon}}}</md-icon>
            </md-icon-button>
        """.trimIndent(),
        "id" to btnId,
        "label" to label,
        "toggle" to (toggle != null),
        "toggledIcon" to toggle?.toggledIcon,
        "enabled" to enabled,
        "small" to (size == Size.SMALL),
        "icon" to icon,
    )

    override fun postRender() {
        getElemById(btnId).onVanillaClick(false) {
            if (disableOnClick)
                setEnabled(false)

            if (toggle != null)
                toggled = !toggled

            onClick()

            if (disableOnClick && existsElemById(btnId))
                setEnabled(true)

            if (stopPropagation)
                it.stopPropagation()
        }

        if (toggle != null) {
            toggled = toggle.startToggled
        }
    }

    fun setEnabled(nowEnabled: Boolean) {
        enabled = nowEnabled
        btnInstance.disabled = !nowEnabled
    }
}