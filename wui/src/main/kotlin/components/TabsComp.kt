package components

import Icons
import components.dropdown.DropdownIconMenuComp
import components.dropdown.DropdownMenuComp
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
) : Component() {

    enum class Type { PRIMARY, SECONDARY }

    data class Tab(
        var title: String,
        val icon: String? = null,
        val onActivate: ((tab: Tab) -> Unit)? = null,
        var active: Boolean = false,
        var visible: Boolean = true,
        val menuOptions: List<DropdownMenuComp.Item> = emptyList(),
        val id: TabID = IdGenerator.nextId(),
        val comp: Component?,
    )

    private lateinit var tabComps: List<Component>
    private lateinit var menus: Map<TabID, DropdownIconMenuComp>


    private val tabsElement: Element
        get() = getElemBySelector("#$dstId > md-tabs")

    private val tabsInstance: MdTabs
        get() = tabsElement.MdTabs()

    override val children: List<Component>
        get() = tabComps + menus.values


    override fun create() = doInPromise {
        tabComps = tabs.mapNotNull { it.comp }

        menus = tabs.mapNotNull {
            if (it.menuOptions.isNotEmpty())
                it.id to DropdownIconMenuComp(Icons.dotsVertical, null, it.menuOptions, parent = this)
            else null
        }.toMap()
    }

    override fun render() = template(
        """
            <md-tabs style='overflow: visible;'>
                {{#tabs}}
                    <{{#prim}}md-primary-tab inline-icon{{/prim}}{{^prim}}md-secondary-tab{{/prim}} id='{{id}}' {{#active}}active{{/active}} class='{{^visible}}display-none{{/visible}}'>
                        {{#icon}}
                            <md-icon slot="icon">{{{icon}}}</md-icon>
                        {{/icon}}
                        <ez-tab-title>{{title}}</ez-tab-title>
                        {{#menuDst}}
                            <ez-tab-menu id='{{menuDst}}'></ez-tab-menu>
                        {{/menuDst}}
                    </{{#prim}}md-primary-tab{{/prim}}{{^prim}}md-secondary-tab{{/prim}}>
                {{/tabs}}
            </md-tabs>
            <ez-tabs-content class='mdtabs'>
            {{#tabComps}}
                <ez-tab-content id="{{dstId}}"></ez-tab-content>
            {{/tabComps}}
            </ez-tabs-content>
            
        """.trimIndent(),
        "prim" to (type == Type.PRIMARY),
        "tabs" to tabs.map {
            mapOf(
                "id" to it.id,
                "title" to it.title,
                "icon" to it.icon,
                "active" to it.active,
                "visible" to it.visible,
                "menuDst" to menus[it.id]?.dstId,
            )
        },
        "tabComps" to tabComps.map {
            mapOf(
                "dstId" to it.dstId
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

    fun getActiveTab() = tabs.first { it.active }

    fun activateTab(id: TabID) {
        tabs.forEach { it.active = (it.id == id) }
        tabsInstance.activeTabIndex = tabs.indexOfFirst { it.id == id }
    }

    private fun refreshContentVisibility(selectedTabIdx: Int) {
        getElemsBySelector("#$dstId > ez-tabs-content > ez-tab-content").forEachIndexed { i, el ->
            if (selectedTabIdx == i)
                el.show(true)
            else
                el.show(false)
        }
    }
}