package components

import kotlinx.dom.addClass
import kotlinx.dom.hasClass
import kotlinx.dom.removeClass
import org.w3c.dom.Element
import pages.exercise_library.ElementQueries
import rip.kspar.ezspa.*
import tmRender

class EzCollComp(
        val items: List<Item>,
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    data class Item(val refId: String,
                    val title: String, val titleLink: String? = null, val typeHtml: String, val isTypeIcon: Boolean,
                    val topAttr: TopAttr? = null, val bottomAttrs: List<BottomAttr>, val attrWidthS: AttrWidthS,
                    val attrWidthM: AttrWidthM, val hasGrowingAttrs: Boolean, val actions: List<Action>)

    data class Action(val iconHtml: String?, val text: String, val minCollWidth: CollMinWidth,
                      val onActivate: (Item) -> Unit, val id: String = IdGenerator.nextId())

    data class BottomAttr(val key: String, val value: String, val shortValueHtml: String, val type: AttrType,
                          val isMutable: Boolean)

    data class TopAttr(val key: String, val value: String, val shortValueHtml: String, val type: AttrType,
                       val isMutable: Boolean, val minWidth: CollMinWidth)

    enum class AttrType {
        STRING, DATETIME, BOOLEAN
    }

    enum class CollMinWidth(val valuePx: String, val maxShowSecondaryValuePx: String) {
        W600("600", "599")
    }

    enum class AttrWidthS(val valuePx: String) {
        W200("200")
    }

    enum class AttrWidthM(val valuePx: String) {
        W300("300")
    }


    private val itemComps = items.mapIndexed { i, item -> EzCollItemComp(item, i, this) }

    override val children: List<Component>
        get() = itemComps

    override fun create() = doInPromise {
    }

    override fun render(): String = tmRender(
            "t-c-ezcoll",
            "isSelectable" to false,
            "items" to itemComps.map { mapOf("dstId" to it.dstId) },
    )

    override fun postRender() {
    }

    override fun renderLoading(): String = "Loading..."
}


class EzCollItemComp(
        private val spec: EzCollComp.Item,
        private val index: Int,
        parent: Component
) : Component(parent) {

    override fun render() = tmRender(
            "t-c-ezcoll-item",
            "idx" to index,
            "hasBottomAttrs" to spec.bottomAttrs.isNotEmpty(),
            "bottomAttrCount" to spec.bottomAttrs.size.toString(),
            "attrWidthS" to spec.attrWidthS.valuePx,
            "attrWidthM" to spec.attrWidthM.valuePx,
            "hasGrowingAttrs" to spec.hasGrowingAttrs,
            "hasActions" to spec.actions.isNotEmpty(),
            "isTypeIcon" to spec.isTypeIcon,
            "typeHtml" to spec.typeHtml,
            "title" to spec.title,
            "titleLink" to spec.titleLink,
            "topAttr" to spec.topAttr?.let {
                mapOf(
                        "key" to it.key,
                        "value" to it.value,
                        "shortValueHtml" to it.shortValueHtml,
                        "isMutable" to it.isMutable,
                        "minCollWidth" to it.minWidth.valuePx,
                        "maxCollWidthInFold" to it.minWidth.maxShowSecondaryValuePx,
                )
            },
            "bottomAttrs" to spec.bottomAttrs.map {
                mapOf(
                        "key" to it.key,
                        "value" to it.value,
                        "shortValueHtml" to it.shortValueHtml,
                        "isMutable" to it.isMutable,
                )
            },
            "actions" to spec.actions.map {
                mapOf(
                        "id" to it.id,
                        "text" to it.text,
                        "iconHtml" to it.iconHtml,
                        "minCollWidth" to it.minCollWidth.valuePx,
                )
            },
            "actionMenuTitle" to "Muuda...",
            "expandItemTitle" to "Laienda",
    )

    override fun postRender() {
        // Maybe can only call in main() if it correctly detects DOM additions
        ElementQueries.init()

        val item = getElemBySelector("ezc-item[ez-item='$index']")!!

        initExpanding(item)
        initActions(item)
    }

    private fun initExpanding(item: Element) {
        val expand = item.getElemBySelector("ez-icon-action[ez-expand-item]")!!
        val fold = item.getElemBySelector("ezc-fold")!!
        val trailer = item.getElemBySelector("ezc-expand-trailer")!!
        val attrs = item.getElemBySelector("ezc-attrs-sizer")!!

        expand.onVanillaClick(false) {
            if (fold.hasClass("display-none")) {
                trailer.addClass("open")
                attrs.addClass("display-none")
                fold.removeClass("display-none")
            } else {
                trailer.removeClass("open")
                fold.addClass("display-none")
                attrs.removeClass("display-none")
            }
        }
    }

    private fun initActions(item: Element) {
        spec.actions.forEach { action ->
            item.getElemsBySelector("[ez-action='${action.id}']").forEach {
                it.onVanillaClick(false) {
                    action.onActivate.invoke(spec)
                }
            }
        }
    }
}
