package components

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.onVanillaClick
import template

// TODO: might want to use IconButtonComp after it's been migrated
class DropdownIconMenuComp(
    val icon: String,
    val items: List<DropdownMenuComp.Item>,
    parent: Component
) : Component(parent) {

    private val iconId = IdGenerator.nextId()

    private val dropdown = DropdownMenuComp(iconId, items, this)

    override val children: List<Component>
        get() = listOf(dropdown)


    override fun render() = template(
        """
            <ez-flex style='position: relative'>
                <md-icon-button id="{{id}}">
                    <md-icon>{{{icon}}}</md-icon>
                </md-icon-button>
                $dropdown
            </ez-flex>
        """.trimIndent(),
        "id" to iconId,
        "icon" to icon,
    )

    override fun postRender() {
        getElemById(iconId).onVanillaClick(false) {
            dropdown.toggleOpen()
        }
    }
}