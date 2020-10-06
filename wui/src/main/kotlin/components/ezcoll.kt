package components

import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise

class EzCollComp(
        val items: List<Item>,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    data class Item(val title: String, val titleLink: String? = null, val typeHtml: String, val topAttr: TopAttr? = null,
                    val bottomAttrs: List<BottomAttr>, val actions: List<Action>)

    data class Action(val svgTemplateId: String?, val text: String, val minCollWidth: CollMinWidth)
    data class BottomAttr(val key: String, val value: String, val shortValueHtml: String, val type: AttrType,
                          val isMutable: Boolean, val minWidth: AttrMinWidth)

    data class TopAttr(val key: String, val value: String, val shortValueHtml: String, val type: AttrType,
                       val isMutable: Boolean, val minWidth: CollMinWidth)

    enum class AttrType {
        STRING, DATETIME, BOOLEAN
    }

    enum class CollMinWidth(value: String) {
        W600("600")
    }

    enum class AttrMinWidth(value: String) {
        W200("200")
    }

    private val itemComps: List<EzCollItemComp>

    init {
        itemComps = items.map { EzCollItemComp(it, this) }
    }

    override val children: List<Component>
        get() = itemComps

    override fun create() = doInPromise {
    }

    override fun render(): String = plainDstStr(*itemComps.map { it.dstId }.toTypedArray())

    override fun postRender() {
    }

    override fun renderLoading(): String = "Loading..."
}


class EzCollItemComp(
        private val itemSpec: EzCollComp.Item,
        parent: Component
) : Component(parent) {

    override fun render() = "comp $itemSpec"

    override fun postRender() {
    }
}
