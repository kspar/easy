package components

import libheaders.MdIconBtn
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import template

class IconButtonComp(
    private val icon: String,
    private val label: String?,
    private var enabled: Boolean = true,
    private val onClick: suspend (() -> Unit),
    private val disableOnClick: Boolean = false,
    private val btnId: String = IdGenerator.nextId(),
    parent: Component
) : Component(parent) {

    private val btnInstance
        get() = getElemById(btnId).MdIconBtn()

    override fun render() = template(
        """
            <md-icon-button id="{{id}}" title="{{label}}" {{^enabled}}disabled{{/enabled}}>
                <md-icon>{{{icon}}}</md-icon>
            </md-icon-button>
        """.trimIndent(),
        "id" to btnId,
        "label" to label,
        "enabled" to enabled,
        "icon" to icon,
    )

    override fun postRender() {
        getElemById(btnId).onVanillaClick(false) {
            if (disableOnClick)
                setEnabled(false)

            onClick()

            if (disableOnClick)
                setEnabled(true)
        }
    }

    fun setEnabled(nowEnabled: Boolean) {
        enabled = nowEnabled
        btnInstance.disabled = !nowEnabled
    }
}