package components

import libheaders.MdTabs
import org.w3c.dom.Element
import rip.kspar.ezspa.*
import show
import template

typealias TabID = String

class TabsComp(
    private val type: Type,
    private val tabs: List<Tab>,
    private val onTabActivate: ((Tab) -> Unit)? = null,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    enum class Type { PRIMARY, SECONDARY }

    data class Tab(
        var title: String,
        val icon: String? = null,
        val onActivate: ((tab: Tab) -> Unit)? = null,
        var active: Boolean = false,
        var visible: Boolean = true,
        val id: TabID = IdGenerator.nextId(),
        val compProvider: (parentComp: TabsComp) -> Component,
    )

    private val tabComps: List<Component> = tabs.map { it.compProvider(this) }

    private val tabsElement: Element
        get() = getElemBySelector("#$dstId > md-tabs")

    private val tabsInstance: MdTabs
        get() = tabsElement.MdTabs()

    override val children: List<Component>
        get() = tabComps


    override fun render() = template(
        """
            <md-tabs>
                {{#tabs}}
                    <{{#prim}}md-primary-tab{{/prim}}{{^prim}}md-secondary-tab{{/prim}} id='{{id}}' {{#active}}active{{/active}} class='{{^visible}}display-none{{/visible}}'>
                        {{#icon}}
                            <md-icon slot="icon">{{{icon}}}</md-icon>
                        {{/icon}}
                        <ez-tab-title>{{title}}</ez-tab-title>
                    </{{#prim}}md-primary-tab{{/prim}}{{^prim}}md-secondary-tab{{/prim}}>
                {{/tabs}}
            </md-tabs>
            <ez-tabs-content class='mdtabs'>
            {{#tabs}}
                <ez-tab-content id="{{compDstId}}"></ez-tab-content>
            {{/tabs}}
            </ez-tabs-content>
            
        """.trimIndent(),
        "prim" to (type == Type.PRIMARY),
        "tabs" to tabs.zip(tabComps).map { (tab, comp) ->
            mapOf(
                "id" to tab.id,
                "title" to tab.title,
                "icon" to tab.icon,
                "compDstId" to comp.dstId,
                "active" to tab.active,
                "visible" to tab.visible,
            )
        },
    )

    override fun postRender() {
        // Show active tab's contents
        val initialTab = tabs.firstOrNull { it.active }
        val initialIdx = if (initialTab != null) tabs.indexOf(initialTab) else 0
        refreshContentVisibility(initialIdx)

        tabsElement.onChange {
            val selectedTabIdx = tabsInstance.activeTabIndex

            // Change contents
            refreshContentVisibility(selectedTabIdx)

            val selectedTab = tabs[selectedTabIdx]

            tabs.forEach { it.active = false }
            selectedTab.active = true

            onTabActivate?.invoke(selectedTab)
            selectedTab.onActivate?.invoke(selectedTab)
        }
    }

    fun setTabVisible(id: TabID, nowVisible: Boolean) {
        tabs.first { it.id == id }.visible = nowVisible
        tabsElement.getElemBySelector("#$id").show(nowVisible)
    }

    fun setTabTitle(id: TabID, newTitle: String) {
        tabs.first { it.id == id }.title = newTitle
        tabsElement.getElemBySelector("#$id ez-tab-title").textContent = newTitle
    }

    fun activateTab(id: TabID) {
        tabs.forEach { it.active = (it.id == id) }
        tabsInstance.activeTabIndex = tabs.indexOfFirst { it.id == id }
    }

    private fun refreshContentVisibility(selectedTabIdx: Int) {
        getElemsBySelector("#$dstId > ez-tabs-content > ez-tab-content").forEachIndexed { i, el ->
            if (selectedTabIdx == i)
                el.setAttribute("active", "")
            else
                el.removeAttribute("active")
        }
    }
}