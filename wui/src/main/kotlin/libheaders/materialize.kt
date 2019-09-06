package libheaders

import org.w3c.dom.Element

@JsName("M")
external object Materialize {
    val Dropdown: MDropdown
    val Sidenav: MSidenav
    val Tooltip: MTooltip
    val Tabs: MTabs
    val Toast: MToast

    fun toast(options: dynamic)
}

external object MDropdown {
    fun init(elements: dynamic, options: dynamic)
}

external object MSidenav {
    fun init(elements: dynamic, options: dynamic)
}

external object MTooltip {
    fun init(elements: dynamic, options: dynamic = definedExternally)
    fun getInstance(element: Element): MTooltipInstance
}

external class MTooltipInstance {
    fun destroy()
}

external object MTabs {
    fun init(elements: dynamic, options: dynamic = definedExternally)
    fun getInstance(element: Element): MTabsInstance
}

external class MTabsInstance {
    fun select(tabId: String)
    fun updateTabIndicator()
}

external class MToast {
    fun dismissAll()
}
