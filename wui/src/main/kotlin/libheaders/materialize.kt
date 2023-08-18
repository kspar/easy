package libheaders

import kotlinx.coroutines.await
import org.w3c.dom.Element
import org.w3c.dom.HTMLSelectElement
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.sleep


@JsModule("@materializecss/materialize")
@JsNonModule
external object Materialize {
    val Dropdown: MDropdown
    val Sidenav: MSidenav
    val Tooltip: MTooltip
    val Tabs: MTabs
    val Materialbox: MMaterialbox
    val FormSelect: MFormSelect
    val Modal: MModal
    val Collapsible: MCollapsible

    fun toast(options: dynamic): MToastInstance

    fun textareaAutoResize(textareaElement: Element)
}


external object MDropdown {
    fun init(elements: dynamic, options: dynamic)
}

external class MDropdownInstance {
    fun open()
    fun close()
}


external object MSidenav {
    fun init(elements: dynamic, options: dynamic): MSidenavInstance
    fun getInstance(element: Element): MSidenavInstance?
}

external class MSidenavInstance {
    fun open()
    fun close()
    val isOpen: Boolean
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

external class MToastInstance {
    fun dismiss()
}


external class MMaterialbox {
    fun init(elements: dynamic, options: dynamic = definedExternally)
}


external class MFormSelect {
    fun init(elements: dynamic, options: dynamic = definedExternally): MFormSelectInstance
}

external class MFormSelectInstance {
    val el: HTMLSelectElement
    val dropdownOptions: Element
    val dropdown: MDropdownInstance
    fun getSelectedValues(): Array<String>
    fun destroy()
}

fun MFormSelectInstance.closePromise(timeMs: Int = 300) = doInPromise {
    this.dropdown.close()
    sleep(timeMs).await()
}


external class MModal {
    fun init(elements: dynamic, options: dynamic = definedExternally): MModalInstance
}

external class MModalInstance {
    fun open()
    fun close()
    fun destroy()
}

external class MCollapsible {
    fun init(element: Element, options: dynamic = definedExternally): MCollapsibleInstance
}

external class MCollapsibleInstance {
    fun open(i: Int)
    fun close(i: Int)
}

fun MCollapsibleInstance.open() = this.open(0)
fun MCollapsibleInstance.close() = this.close(0)
