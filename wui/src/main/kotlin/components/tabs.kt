package components

import IdGenerator
import getElemById
import libheaders.MTabsInstance
import libheaders.Materialize
import objOf
import spa.Component
import tmRender


class CardTabsComp(
        parent: Component?,
        private val onActivateTab: ((String) -> Unit)? = null,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    data class Tab(val id: String, val title: String, val comp: Component, val preselected: Boolean = false)

    private val tabsId = IdGenerator.nextId()
    private lateinit var tabs: List<Tab>
    private lateinit var mtabs: MTabsInstance

    override val children: List<Component>
        get() = tabs.map { it.comp }


    override fun render(): String = tmRender("t-c-card-tabs",
            "tabs" to tabs.map { mapOf("dstId" to it.comp.dstId, "title" to it.title) },
            "tabsId" to tabsId
    )

    override fun postRender() {
        mtabs = Materialize.Tabs.init(getElemById(tabsId),
                objOf("onShow" to { onActivateTab?.invoke(tabs[mtabs.index].id) }))

        tabs.firstOrNull { it.preselected }?.let { mtabs.select(it.comp.dstId) }
    }

    fun setTabs(tabs: List<Tab>) {
        this.tabs = tabs
    }
}
