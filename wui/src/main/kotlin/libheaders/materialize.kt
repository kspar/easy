package libheaders

import org.w3c.dom.Element

@JsName("M")
external object Materialize {
    val Dropdown: MDropdown
    val Sidenav: MSidenav
    val Tooltip: MTooltip
    val Tabs: MTabs
    val Toast: MToast
    val Materialbox: MMaterialbox
    val FormSelect: MFormSelect

    fun toast(options: dynamic): MToastInstance
}

external object MDropdown {
    fun init(elements: dynamic, options: dynamic)
}

external object MSidenav {
    fun init(elements: dynamic, options: dynamic)
}

external object MTooltip {
    fun init(elements: dynamic, options: dynamic = definedExternally)
    fun getInstance(element: Element): MTooltipInstance?
}

external class MTooltipInstance {
    fun destroy()
}

external object MTabs {
    fun init(elements: dynamic, options: dynamic = definedExternally): MTabsInstance
    fun getInstance(element: Element): MTabsInstance?
}

external class MTabsInstance {
    val index: Int
    fun select(tabId: String)
    fun updateTabIndicator()
}

external class MToast {
    fun dismissAll()
}

external class MToastInstance {
    fun dismiss()
}

external class MMaterialbox {
    fun init(elements: dynamic, options: dynamic = definedExternally)
}

external class MFormSelectInstance {
    val el: Element
    fun getSelectedValues(): dynamic
}

external class MFormSelect {
    fun init(elements: dynamic, options: dynamic = definedExternally) : MFormSelectInstance
}
