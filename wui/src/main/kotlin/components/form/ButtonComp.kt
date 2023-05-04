package components.form

import hide
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import org.w3c.dom.HTMLButtonElement
import rip.kspar.ezspa.*
import show
import template

class ButtonComp(
    private val type: Type,
    // TODO: label can be empty
    private val label: String,
    private val iconHtml: String? = null,
    private val onClick: suspend (() -> Unit),
    private val isEnabledInitial: Boolean = true,
    private val clickedLabel: String? = null,
    private val showClickedLoading: Boolean = true,
    private val postClick: (suspend (() -> Unit))? = null,
    parent: Component
) : Component(parent) {

    enum class Type {
        PRIMARY, FLAT, DANGER, PRIMARY_ROUND,
    }

    private val btnId = IdGenerator.nextId()

    private val element: HTMLButtonElement
        get() = getElemByIdAs(btnId)

    override fun render() = template(
        """
            <button id="{{id}}" class="{{#isPrimary}}btn waves-light{{/isPrimary}} {{#isSecondary}}secondary-btn btn-flat{{/isSecondary}} {{#isDanger}}btn danger waves-light{{/isDanger}} {{#isPrimaryRound}}btn-floating waves-light{{/isPrimaryRound}} {{#isDisabled}}disabled{{/isDisabled}} waves-effect">
                <ez-btn-content>
                    {{#iconHtml}}<ez-btn-icon>{{{iconHtml}}}</ez-btn-icon>{{/iconHtml}}
                    <ez-spinner class="preloader-wrapper active display-none">
                        <div class="spinner-layer"><div class="circle-clipper left"><div class="circle"></div></div><div class="gap-patch"><div class="circle"></div></div><div class="circle-clipper right"><div class="circle"></div></div></div>
                    </ez-spinner>
                    <ez-btn-text>{{text}}</ez-btn-text>
                </ez-btn-content>
            </button>
        """.trimIndent(),
        "id" to btnId,
        "isPrimary" to (type == Type.PRIMARY),
        "isSecondary" to (type == Type.FLAT),
        "isDanger" to (type == Type.DANGER),
        "isPrimaryRound" to (type == Type.PRIMARY_ROUND),
        "isDisabled" to !isEnabledInitial,
        "iconHtml" to iconHtml,
        "text" to label,
    )

    override fun postRender() {
        element.onVanillaClick(true) {
            val text = element.getElemBySelector("ez-btn-text")
            val icon = element.getElemBySelectorOrNull("ez-btn-icon")
            val loader = element.getElemBySelector("ez-spinner")
            val activeHtml = text.innerHTML

            disable()

            if (showClickedLoading) {
                icon?.hide()
                loader.show()
            }
            if (clickedLabel != null) {
                text.textContent = clickedLabel
            }
            try {
                onClick()
            } finally {
                // element might've been destroyed on click
                if (getElemByIdOrNull(btnId) != null) {
                    loader.hide()
                    icon?.show()
                    text.innerHTML = activeHtml
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