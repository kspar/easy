package components

import libheaders.MTabsInstance
import libheaders.Materialize
import objOf
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemById
import tmRender


class PageTabsComp(
    private val tabs: List<Tab>,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    data class Tab(
        val title: String,
        val onActivate: ((tabId: String) -> Unit)? = null,
        val id: String = IdGenerator.nextId(),
        val preselected: Boolean = false,
        val compProvider: (parentComp: PageTabsComp) -> Component,
    )

    private val tabsId = IdGenerator.nextId()

    private lateinit var tabComps: List<Component>

    private lateinit var mtabs: MTabsInstance

    override val children: List<Component>
        get() = tabComps

    override fun create() = doInPromise {
        tabComps = tabs.map { it.compProvider(this) }
    }

    override fun render(): String = tmRender(
        "t-c-page-tabs",
        "tabs" to tabs.zip(tabComps).map { (tab, comp) ->
            mapOf(
                "id" to tab.id,
                "label" to tab.title,
                "compDstId" to comp.dstId,
                "isPreselected" to tab.preselected,
            )
        },
        "tabsId" to tabsId
    )

    override fun postRender() {
        mtabs = Materialize.Tabs.init(
            getElemById(tabsId),
            objOf("onShow" to {
                val tab = tabs[mtabs.index]
                tab.onActivate?.invoke(tab.id)
            })
        )
    }

    override fun postChildrenBuilt() {
        mtabs.updateTabIndicator()
    }

    fun getSelectedTab(): Tab = tabs[mtabs.index]

    fun setSelectedTab(tab: Tab) {
        setSelectedTabById(tab.id)
    }

    fun setSelectedTabById(tabId: String) {
        mtabs.select(tabId)
    }
}
