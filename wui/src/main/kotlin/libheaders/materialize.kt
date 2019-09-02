package libheaders

@JsName("M")
external object Materialize {
    val Dropdown: MDropdown
    val Sidenav: MSidenav
    val Tooltip: MTooltip
    val Tabs: MTabs

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
    fun getInstance(element: dynamic): MTooltipInstance
}

external class MTooltipInstance {
    fun destroy()
}

external object MTabs {
    fun init(elements: dynamic, options: dynamic = definedExternally)
}
