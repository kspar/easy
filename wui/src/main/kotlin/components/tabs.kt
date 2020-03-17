package components

import IdGenerator
import getElemById
import libheaders.Materialize
import spa.Component
import tmRender


class CardTabsComp(
        parent: Component?,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    private lateinit var tabs: Map<String, Component>
    private val tabsId = IdGenerator.nextId()

    override val children: List<Component>
        get() = tabs.values.toList()


    override fun render(): String = tmRender("t-c-card-tabs",
            "tabs" to tabs.map { mapOf("dstId" to it.value.dstId, "title" to it.key) },
            "tabsId" to tabsId
    )

    override fun postRender() {
        Materialize.Tabs.init(getElemById(tabsId))
    }

    fun setTabs(tabs: Map<String, Component>) {
        this.tabs = tabs
    }
}
