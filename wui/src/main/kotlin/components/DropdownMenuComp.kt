package components

import debug
import libheaders.MdMenu
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemById
import template

// Dynamically triggered dropdown menu without an anchor
class DropdownMenuComp(
    val anchorId: String,
    val items: List<Item>,
    parent: Component
) : Component(parent) {

    data class Item(
        val label: String,
        val icon: String? = null,
        val onSelected: (suspend (Item) -> Unit),
        val isDisabled: Boolean = false,
        val id: String = IdGenerator.nextId()
    )

    private val elementId = IdGenerator.nextId()

    override fun render() = template(
        """
            <md-menu id="{{id}}" anchor="{{anchorId}}">
                {{#items}}
                    <md-menu-item id='{{id}}' {{#disabled}}disabled='true'{{/disabled}}>
                        {{#icon}}
                            <md-icon slot="start">{{{icon}}}</md-icon>
                        {{/icon}}
                        <div slot="headline">{{label}}</div>
                    </md-menu-item>
                {{/items}}
            </md-menu>
        """.trimIndent(),
        "id" to elementId,
        "anchorId" to anchorId,
        "items" to items.map {
            mapOf(
                "id" to it.id,
                "label" to it.label,
                "icon" to it.icon,
                "disabled" to it.isDisabled,
            )
        }
    )

    override fun postRender() {
        items.forEach { item ->
            getElemById(item.id).addEventListener("close-menu", {
                // ignore escape key "selections"
                // https://github.com/material-components/material-web/blob/6b95a13ee1fd4203140aeda89c24c7afb8acdde1/menu/internal/controllers/shared.ts#L87
                val closingKey = (it.asDynamic().detail.reason.key as String?)?.lowercase()
                if (closingKey == "escape")
                    return@addEventListener

                debug { "Item '${item.label}' selected" }
                doInPromise {
                    item.onSelected(item)
                }
            })
        }
    }

    fun toggleOpen() {
        setOpen(!getElemById(elementId).MdMenu().open)
    }

    fun setOpen(nowOpen: Boolean) {
        getElemById(elementId).MdMenu().open = nowOpen
    }

    fun setSelected(itemId: String?) {
        items.forEach {
            if (it.id == itemId)
                getElemById(it.id).setAttribute("selected", "")
            else
                getElemById(it.id).removeAttribute("selected")
        }
    }
}