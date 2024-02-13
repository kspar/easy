package components

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import template

class TabsComp(
    private val type: Type,
    private val tabs: List<Tab>,
    trailerComponent: ((parentComp: PageTabsComp) -> Component)? = null,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    enum class Type { PRIMARY, SECONDARY }

    data class Tab(
        val title: String,
        val icon: String? = null,
        val onActivate: ((tabI: Tab) -> Unit)? = null,
        val id: String = IdGenerator.nextId(),
        val active: Boolean = false,
        val compProvider: (parentComp: TabsComp) -> Component,
    )

    private val tabComps: List<Component> = tabs.map { it.compProvider(this) }

//    private val tabsInstance: MdTabs
//        get() = getElemBySelector("#$dstId md-tabs")

    override val children: List<Component>
        get() = tabComps


    override fun render()= template(
        """
            <md-tabs>
                {{#tabs}}
                    <{{#prim}}md-primary-tab{{/prim}}{{^prim}}md-secondary-tab{{/prim}} {{#active}}active{{/active}}>
                        {{#icon}}
                            <md-icon slot="icon">{{{icon}}}</md-icon>
                        {{/icon}}
                        {{title}}
                    </{{#prim}}md-primary-tab{{/prim}}{{^prim}}md-secondary-tab{{/prim}}>
                {{/tabs}}
            </md-tabs>
        """.trimIndent(),
        "prim" to (type == Type.PRIMARY),
        "tabs" to tabs.zip(tabComps).map { (tab, comp) ->
            mapOf(
                "title" to tab.title,
                "icon" to tab.icon,
                "compDstId" to comp.dstId,
                "active" to tab.active,
            )
        },

    )

    override fun postRender() {

    }
}