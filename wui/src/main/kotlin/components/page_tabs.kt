package components

import libheaders.MTabsInstance
import libheaders.Materialize
import rip.kspar.ezspa.*
import show
import template


class PageTabsComp(
    private val type: Type = Type.TOP_LEVEL,
    private val tabs: List<Tab>,
    trailerComponent: ((parentComp: PageTabsComp) -> Component)? = null,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    enum class Type { TOP_LEVEL, SUBPAGE }

    data class Tab(
        val title: String,
        val onActivate: ((tabId: String) -> Unit)? = null,
        val id: String = IdGenerator.nextId(),
        val preselected: Boolean = false,
        val hidden: Boolean = false,
        val compProvider: (parentComp: PageTabsComp) -> Component,
    )

    private val tabsId = IdGenerator.nextId()

    private val tabComps: List<Component> = tabs.map { it.compProvider(this) }
    private val trailerComp = trailerComponent?.invoke(this)

    private lateinit var mtabs: MTabsInstance

    override val children: List<Component>
        get() = tabComps + listOfNotNull(trailerComp)

    override fun render(): String = template(
        """
        <ez-tabs class="{{#toplevel}}toplevel{{/toplevel}} {{#subpage}}subpage{{/subpage}}">
            <ez-tabs-header>
                <ul id="{{tabsId}}" class="tabs">
                    <!-- On one line, avoid whitespace between items -->
                    {{#tabs}}<li id='tab-{{id}}' class="tab {{#isHidden}}display-none{{/isHidden}}"><a href="#{{id}}" class="{{#isPreselected}}active{{/isPreselected}}">{{label}}</a></li>{{/tabs}}
                </ul>
                {{#trailerElementId}}
                    <ez-tabs-trailer id="{{trailerElementId}}"></ez-tabs-trailer>
                {{/trailerElementId}}
            </ez-tabs-header>
            <ez-tabs-content>
                {{#tabs}}
                    <ez-tab-content id="{{id}}">
                        <ez-dst id="{{compDstId}}"></ez-dst>
                    </ez-tab-content>
                {{/tabs}}
            </ez-tabs-content>
        </ez-tabs>
    """.trimIndent(),
        "toplevel" to (type == Type.TOP_LEVEL),
        "subpage" to (type == Type.SUBPAGE),
        "tabs" to tabs.zip(tabComps).map { (tab, comp) ->
            mapOf(
                "id" to tab.id,
                "label" to tab.title,
                "compDstId" to comp.dstId,
                "isPreselected" to tab.preselected,
                "isHidden" to tab.hidden,
            )
        },
        "tabsId" to tabsId,
        "trailerElementId" to trailerComp?.dstId,
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
        getElemById("tab-$tabId").show()
        mtabs.select(tabId)
    }

    fun getTabComps() = tabComps

    fun refreshIndicator() = mtabs.updateTabIndicator()

    fun setTabTitle(tabId: String, title: String) {
        getElemBySelector("#tab-$tabId > a").textContent = title
    }
}
