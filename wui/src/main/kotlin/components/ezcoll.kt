package components

import debug
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass
import libheaders.Materialize
import libheaders.closePromise
import objOf
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.events.Event
import rip.kspar.ezspa.*
import tmRender

class EzCollComp<P>(
    items: List<Item<P>>,
    private val strings: Strings,
    private val massActions: List<MassAction<P>> = emptyList(),
    private val filterGroups: List<FilterGroup<P>> = emptyList(),
    private val sorters: List<Sorter<P>> = emptyList(),
    private val useFirstSorterAsDefault: Boolean = true,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    data class Item<P>(
        val props: P,
        val type: ItemType,
        val title: String,
        val titleStatus: TitleStatus = TitleStatus.NORMAL,
        val titleLink: String? = null,
        val topAttr: Attr<P>? = null,
        val bottomAttrs: List<Attr<P>> = emptyList(),
        val isSelectable: Boolean = false,
        val actions: List<Action<P>> = emptyList(),
        val attrWidthS: AttrWidthS = AttrWidthS.W200,
        val attrWidthM: AttrWidthM = AttrWidthM.W300,
        val hasGrowingAttrs: Boolean = false,
        val id: String = IdGenerator.nextId(),
    )

    interface ItemType {
        val isIcon: Boolean
        val html: String
    }

    data class ItemTypeIcon(val iconHtml: String) : ItemType {
        override val isIcon = true
        override val html = iconHtml
    }

    data class ItemTypeText(val text: String) : ItemType {
        override val isIcon = false
        override val html = text
    }

    data class Action<P>(
        val iconHtml: String,
        val text: String,
        val onActivate: suspend (Item<P>) -> Result,
        val showShortcutIcon: Boolean = false,
        val shortcutMinCollWidth: CollMinWidth = CollMinWidth.W600,
        val id: String = IdGenerator.nextId()
    )

    data class MassAction<P>(
        val iconHtml: String,
        val text: String,
        val onActivate: suspend (List<Item<P>>) -> Result,
        val id: String = IdGenerator.nextId()
    )

    sealed interface Result
    object ResultUnmodified : Result
    data class ResultModified<P>(val items: List<Item<P>>) : Result

    data class Strings(val totalItemsSingular: String, val totalItemsPlural: String)

    data class FilterGroup<P>(val groupLabel: String, val filters: List<Filter<P>>)

    data class Filter<P>(val label: String, val predicate: (Item<P>) -> Boolean, val id: String = IdGenerator.nextId())

    data class Sorter<P>(val label: String, val comparator: Comparator<Item<P>>, val id: String = IdGenerator.nextId())

    abstract class Attr<P> {
        abstract val key: String
        abstract val onClick: (suspend (Item<P>) -> Result)?
        abstract val topAttrMinWidth: CollMinWidth
        val id: String = IdGenerator.nextId()

        abstract fun getShortValueHtml(): String
        abstract fun getLongValue(): String
    }

    data class SimpleAttr<P, ValueType : Any>(
        override val key: String,
        val shortValue: ValueType,
        val shortValuePrefix: String = "",
        val longValue: ValueType = shortValue,
        override val onClick: (suspend (Item<P>) -> Result)? = null,
        override val topAttrMinWidth: CollMinWidth = CollMinWidth.W600
    ) : Attr<P>() {
        override fun getShortValueHtml(): String = shortValuePrefix + shortValue
        override fun getLongValue(): String = longValue.toString()
    }

    data class RenderedAttr<P, ValueType>(
        override val key: String,
        val value: ValueType,
        val renderShortValue: (ValueType) -> String,
        val shortValuePrefix: String = "",
        val renderLongValue: (ValueType) -> String = renderShortValue,
        override val onClick: (suspend (Item<P>) -> Result)? = null,
        override val topAttrMinWidth: CollMinWidth = CollMinWidth.W600
    ) : Attr<P>() {
        override fun getShortValueHtml(): String = shortValuePrefix + renderShortValue(value)
        override fun getLongValue(): String = renderLongValue(value)
    }

    data class ListAttr<P, ItemType : Any>(
        override val key: String,
        val items: MutableList<ListAttrItem<ItemType>> = mutableListOf(),
        val shortValuePrefix: String = "",
        val maxItemsShownInShort: Int = 2,
        val separator: String = ", ",
        override val onClick: (suspend (Item<P>) -> Result)? = null,
        override val topAttrMinWidth: CollMinWidth = CollMinWidth.W600
    ) : Attr<P>() {
        override fun getShortValueHtml(): String {
            if (items.isEmpty())
                return ""

            return items.joinToString(
                separator,
                shortValuePrefix,
                limit = maxItemsShownInShort,
                truncated = "+${items.size - maxItemsShownInShort}"
            ) { it.shortValue.toString() }
        }

        override fun getLongValue(): String {
            return items.joinToString(separator) { it.longValue.toString() }
        }
    }

    data class ListAttrItem<ItemType : Any>(val shortValue: ItemType, val longValue: ItemType = shortValue)

    enum class TitleStatus { NORMAL, INACTIVE }

    enum class CollMinWidth(val valuePx: String, val maxShowSecondaryValuePx: String) {
        W600("600", "599")
    }

    enum class AttrWidthS(val valuePx: String) {
        W200("200")
    }

    enum class AttrWidthM(val valuePx: String) {
        W300("300")
    }


    // All items including hidden (filtered) items - see init below
    // The order in this list is not necessarily the display order (which is set by item.orderingIndex)
    private var items: List<EzCollItemComp<P>>

    // Currently checked items
    private var checkedItems: MutableList<EzCollItemComp<P>> = mutableListOf()

    // Currently applied filters - list of filter groups where each group is a list of applied filters
    private var activatedFilters: List<List<Filter<P>>> = emptyList()

    // Currently applied sorter, if null then items are displayed in the created order
    private var activeSorter: Sorter<P>? = if (useFirstSorterAsDefault) sorters.firstOrNull() else null

    // Getters because items can change
    private val hasSelection: Boolean
        get() = items.any { it.spec.isSelectable }

    private val hasFiltering: Boolean
        get() = filterGroups.isNotEmpty() && items.isNotEmpty()

    private val hasSorting: Boolean
        get() = sorters.isNotEmpty() && items.isNotEmpty()

    private val collId = IdGenerator.nextId()

    init {
        val specs = if (sorters.isNotEmpty() && useFirstSorterAsDefault) {
            items.sortedWith(sorters.first().comparator)
        } else
            items

        this.items = specs.mapIndexed { i, spec ->
            EzCollItemComp(spec, i, ::itemSelectClicked, ::removeItem, this)
        }
    }


    override val children: List<Component>
        get() = items

    override fun render(): String {
        val activatedFilterIds = activatedFilters.flatMap { it.map { it.id } }

        return tmRender(
            "t-c-ezcoll",
            "collId" to collId,
            "hasSelection" to hasSelection,
            "hasFiltering" to hasFiltering,
            "hasOrdering" to hasSorting,
            "selectActions" to massActions.map { mapOf("actionHtml" to "${it.iconHtml} ${it.text}", "id" to it.id) },
            "items" to items.map { mapOf("dstId" to it.dstId, "idx" to it.orderingIndex) },
            "applyLabel" to "Rakenda...",
            "applyExpandIcon" to Icons.dropdownBtnExpand,
            "applyShortIcon" to Icons.dotsHorizontal,
            "filterLabel" to "Filtreeri",
            "orderLabel" to "Järjesta",
            "removeFiltersLabel" to "Eemalda filtrid",
            "filterGroups" to filterGroups.map {
                mapOf(
                    "groupLabel" to it.groupLabel,
                    "filterOptions" to it.filters.map {
                        mapOf(
                            "value" to it.id,
                            "optionLabel" to it.label,
                            "isSelected" to (activatedFilterIds.contains(it.id))
                        )
                    })
            },
            "sorters" to sorters.map {
                mapOf(
                    "id" to it.id,
                    "label" to it.label,
                    "isSelected" to (it == activeSorter)
                )
            },
        )
    }

    override fun postRender() {
        if (hasSelection)
            initSelection()
        if (hasFiltering)
            initFiltering()
        if (hasSorting)
            initSorting()
    }

    override fun postChildrenBuilt() {
        if (hasSelection)
            selectItemsBasedOnChecked()
        if (hasFiltering)
            updateFiltering()
        // No need to update sorting, order styles are rendered into HTML
    }


    private fun initSelection() {
        // Init mass actions
        Materialize.Dropdown.init(
            getElemById(collId).getElemBySelector("ezc-ctrl-left .dropdown-trigger"),
            objOf("coverTrigger" to false, "constrainWidth" to false, "closeOnClick" to true)
        )
        massActions.forEach { action ->
            getElemById(collId).getElemBySelector("[ez-mass-action='${action.id}']").onVanillaClick(false) {
                invokeMassAction(action)
            }
        }

        // Init clicking 'select all' checkbox
        val allCheckboxEl = getElemByIdAs<HTMLInputElement>("ezc-select-all-$collId")
        allCheckboxEl.onVanillaClick(false) {

            val isChecked = allCheckboxEl.checked
            val visibleItems = calculateVisibleItems()

            checkedItems = if (isChecked) visibleItems.toMutableList() else mutableListOf()

            visibleItems.forEach { it.setSelected(isChecked) }
            updateSelection()
        }
    }

    private fun initFiltering() {
        // Init filter dropdown
        val select = Materialize.FormSelect.init(
            getElemById(collId).getElemBySelector("ezc-ctrl-filter select"),
            objOf("dropdownOptions" to objOf("constrainWidth" to false))
        )

        val openFilterSelectListener =
            getElemById(collId).getElemBySelector("ezc-ctrl-filter ez-icon-action").onVanillaClick(false) {
                select.dropdown.open()
            }

        // Init filter change
        val filterChangeListener = select.el.onChange {
            val selectedValues = select.getSelectedValues()
            debug { "Selected filter IDs: ${selectedValues.joinToString(", ", "[", "]")}" }

            val appliedFilterGroups = filterGroups.mapNotNull {
                val selectedFilters = it.filters.filter { selectedValues.contains(it.id) }
                selectedFilters.ifEmpty { null }
            }

            activatedFilters = appliedFilterGroups
            debug { "Applied filter groups: ${appliedFilterGroups.map { it.map { it.label } }}" }

            updateFiltering()
        }

        // Init remove all filters
        getElemById(collId).getElemBySelector("ezc-ctrl-filter li.optgroup:first-child").onVanillaClick(false) {
            debug { "Removing all filters" }

            // Unselect all options
            getElemById(collId).getElemsBySelector("ezc-ctrl-filter select option").forEach {
                it as HTMLOptionElement
                it.selected = false
            }

            // Remove filters and update
            activatedFilters = emptyList()
            updateFiltering()

            // Destroy select
            select.closePromise().await()
            select.destroy()

            // Remove event listeners to prevent duplicates
            openFilterSelectListener.remove()
            filterChangeListener.remove()

            // Recreate filtering
            initFiltering()
        }
    }

    private fun calculateVisibleItems(): List<EzCollItemComp<P>> {
        return items.filter { item ->
            activatedFilters.all { filterGroup ->
                filterGroup.any { filter -> filter.predicate(item.spec) }
            }
        }
    }

    private fun updateFiltering() {
        if (!hasFiltering)
            return

        val isFilterActive = activatedFilters.isNotEmpty()
        val visibleItems = calculateVisibleItems()

        updateVisibleItems(visibleItems)
        updateShownCount(visibleItems.size, isFilterActive)
        updateFilterIcon(isFilterActive)

        updateCheckedItemsBasedOnVisible(visibleItems)
    }

    private fun updateVisibleItems(visibleItems: List<EzCollItemComp<P>>) {
        items.forEach { it.setVisible(visibleItems.contains(it)) }
    }

    private fun updateCheckedItemsBasedOnVisible(visibleItems: List<EzCollItemComp<P>>) {
        val (visibleCheckedItems, invisibleItems) = checkedItems.partition { visibleItems.contains(it) }
        checkedItems = visibleCheckedItems.toMutableList()
        invisibleItems.forEach { it.setSelected(false) }
        updateSelection()
    }

    private fun updateFilterIcon(isFilterActive: Boolean) {
        getElemById(collId).getElemBySelector("ezc-ctrl-filter")
            .setAttribute("filter", if (isFilterActive) "on" else "off")
    }

    private fun updateShownCount(visibleItemsCount: Int, isFilterActive: Boolean) {
        val totalItemsCount = items.size

        if (isFilterActive) {
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-icon").clear()
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-count").textContent = "$visibleItemsCount / $totalItemsCount"
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-name").textContent = "kuvatud"
        } else {
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-icon").innerHTML = "Σ"
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-count").textContent = totalItemsCount.toString()
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-name").textContent = if (totalItemsCount == 1) strings.totalItemsSingular else strings.totalItemsPlural
        }
    }

    private suspend fun invokeMassAction(action: MassAction<P>) {
        val selectedItems = checkedItems.map { it.spec }
        debug { "Mass action ${action.text} on: ${selectedItems.map { it.title }}" }

        val result = action.onActivate(selectedItems)
        if (result is ResultUnmodified) {
            debug { "Action result: unmodified" }
            return
        }

        val returnedItems = result.unsafeCast<ResultModified<P>>().items
        debug { "Returned items: ${returnedItems.map { it.title }}" }

        // Replace or delete items based on returned list
        val processedItems = selectedItems.mapNotNull { initial ->
            returnedItems.singleOrNull { initial.id == it.id }
        }
        val finalItems = items.mapNotNull { item ->
            val processed = processedItems.singleOrNull { it.id == item.spec.id }
            val selected = selectedItems.singleOrNull { it.id == item.spec.id }
            when {
                // Item was processed
                processed != null -> EzCollItemComp(
                    processed,
                    item.orderingIndex,
                    ::itemSelectClicked,
                    ::removeItem,
                    this
                )
                // Item was selected and not returned i.e. was deleted
                selected != null -> null
                // Item wasn't selected, keep
                else -> item
            }
        }

        // TODO: allow adding items

        items = finalItems
        debug { "Final items: ${finalItems.map { it.spec.title }}" }

        checkedItems = mutableListOf()
        super.rebuild(false)
    }

    private fun itemSelectClicked(item: EzCollItemComp<P>, isChecked: Boolean) {
        if (isChecked) {
            debug { "Item ${item.spec.title} checked" }
            checkedItems.add(item)
        } else {
            debug { "Item ${item.spec.title} unchecked" }
            checkedItems.remove(item)
        }

        updateSelection()
    }

    private fun removeItem(item: EzCollItemComp<P>) {
        debug { "item ${item.spec.title} removed" }
        items = items - item
        checkedItems.remove(item)
        super.rebuild(false)
    }

    private fun selectItemsBasedOnChecked() {
        val visibleItems = calculateVisibleItems()
        visibleItems.forEach { it.setSelected(checkedItems.contains(it)) }
        updateSelection()
    }

    private fun updateSelection() {
        if (!hasSelection)
            return

        updateCheckedCount()
        updateAllCheckbox()
        updateMassActionMenu()
    }

    private fun updateMassActionMenu() {
        val actionMenuEl = getElemById(collId).getElemBySelector("ezc-ctrl-left .dropdown-trigger")
        if (checkedItems.isEmpty())
            actionMenuEl.addClass("disabled")
        else
            actionMenuEl.removeClass("disabled")
    }

    private fun updateCheckedCount() {
        val selectedCountEl = getElemById(collId).getElemBySelector("ezc-ctrl-selected")
        if (checkedItems.isEmpty())
            selectedCountEl.textContent = ""
        else
            selectedCountEl.textContent = "${checkedItems.size} valitud"
    }

    private fun updateAllCheckbox() {
        val allCheckboxEl = getElemById(collId).getElemBySelector(".ezc-all-checkbox input") as HTMLInputElement

        val currentStatus = allCheckboxEl.checked to allCheckboxEl.indeterminate

        val newStatus = when {
            checkedItems.isEmpty() -> false to false
            checkedItems.size == calculateVisibleItems().size -> true to false
            else -> false to true
        }

        if (currentStatus != newStatus) {
            debug { "All checkbox status changed: $currentStatus -> $newStatus" }
            allCheckboxEl.checked = newStatus.first
            allCheckboxEl.indeterminate = newStatus.second
            allCheckboxEl.dispatchEvent(Event("change"))
        } else {
            debug { "All checkbox status unchanged: $currentStatus" }
        }
    }

    private fun initSorting() {
        Materialize.Dropdown.init(
            getElemById(collId).getElemBySelector("ezc-ctrl-order .dropdown-trigger"),
            objOf("closeOnClick" to false, "coverTrigger" to false, "constrainWidth" to false)
        )
        sorters.forEach { s ->
            getElemById(collId).getElemBySelector("[ez-sorter='${s.id}']").onVanillaClick(false) {
                debug { "Sorter: ${s.label}" }
                activeSorter = s
                updateSorting()
            }
        }
    }

    private fun updateSorting() {
        val currentSorter = activeSorter
        if (currentSorter != null) {
            val compCompare = Comparator<EzCollItemComp<P>> { a, b -> currentSorter.comparator.compare(a.spec, b.spec) }

            items = items.sortedWith(compCompare)
            items.forEachIndexed { i, item ->
                item.orderingIndex = i
                item.updateOrderingIndex()
            }
        }
    }
}


class EzCollItemComp<P>(
    var spec: EzCollComp.Item<P>,
    var orderingIndex: Int,
    private val onCheckboxClicked: (EzCollItemComp<P>, Boolean) -> Unit,
    private val onDelete: (EzCollItemComp<P>) -> Unit,
    parent: Component
) : Component(parent) {

    private val itemEl: Element
        get() = getElemById(spec.id)

    private var isSelected: Boolean = false

    private var isExpanded: Boolean = false

    override fun render() = tmRender(
        "t-c-ezcoll-item",
        "itemId" to spec.id,
        "isSelectable" to spec.isSelectable,
        "hasBottomAttrs" to spec.bottomAttrs.isNotEmpty(),
        "bottomAttrCount" to spec.bottomAttrs.size.toString(),
        "attrWidthS" to spec.attrWidthS.valuePx,
        "attrWidthM" to spec.attrWidthM.valuePx,
        "hasGrowingAttrs" to if (spec.bottomAttrs.size == 1) true else spec.hasGrowingAttrs,
        "hasActions" to spec.actions.isNotEmpty(),
        "isTypeIcon" to spec.type.isIcon,
        "typeHtml" to spec.type.html,
        "title" to spec.title,
        "titleLink" to spec.titleLink,
        "titleInactive" to (spec.titleStatus == EzCollComp.TitleStatus.INACTIVE),
        "topAttr" to spec.topAttr?.let {
            mapOf(
                "id" to it.id,
                "key" to it.key,
                "value" to it.getLongValue(),
                "shortValueHtml" to it.getShortValueHtml(),
                "isActionable" to (it.onClick != null),
                "minCollWidth" to it.topAttrMinWidth.valuePx,
                "maxCollWidthInFold" to it.topAttrMinWidth.maxShowSecondaryValuePx,
            )
        },
        "bottomAttrs" to spec.bottomAttrs.map {
            mapOf(
                "id" to it.id,
                "key" to it.key,
                "value" to it.getLongValue(),
                "shortValueHtml" to it.getShortValueHtml(),
                "isActionable" to (it.onClick != null),
            )
        },
        "actions" to spec.actions.map {
            mapOf(
                "id" to it.id,
                "text" to it.text,
                "iconHtml" to it.iconHtml,
                "showShortcut" to it.showShortcutIcon,
                "minCollWidth" to it.shortcutMinCollWidth.valuePx,
            )
        },
        "actionMenuTitle" to "Muuda...",
        "expandItemTitle" to "Laienda",
    )

    override fun postRender() {
        initExpanding()
        initActions()
        initActionableAttrs()

        if (spec.isSelectable) {
            initSelection()
            updateSelectionState()
        }

        // TODO: atm item is expandable if there are bottom attrs
        // TODO: but should also be expandable if there is a top attr which doesn't fit

        updateExpanded()
    }


    fun setVisible(isVisible: Boolean) {
        if (isVisible)
            itemEl.removeClass("display-none")
        else
            itemEl.addClass("display-none")
    }

    fun setSelected(selected: Boolean) {
        if (isSelected == selected)
            return

        isSelected = selected
        updateSelectionState()
        debug { "Item ${spec.title} now selected: $isSelected" }
    }

    fun updateOrderingIndex() {
        // TODO: element.style missing from kotlin?
        itemEl.parentElement!!.setAttribute("style", "order: $orderingIndex")
    }

    private fun initExpanding() {
        val expand = itemEl.getElemBySelector("ez-icon-action[ez-expand-item]")
        expand.onVanillaClick(false) {
            isExpanded = !isExpanded
            updateExpanded()
        }
    }

    private fun updateExpanded() {
        val fold = itemEl.getElemBySelector("ezc-fold")
        val trailer = itemEl.getElemBySelector("ezc-expand-trailer")
        val attrs = itemEl.getElemBySelector("ezc-bottom-attrs")
        if (isExpanded) {
            trailer.addClass("open")
            attrs.addClass("display-none")
            fold.removeClass("display-none")
        } else {
            trailer.removeClass("open")
            fold.addClass("display-none")
            attrs.removeClass("display-none")
        }
    }

    private fun initActions() {
        // Init shortcut icon actions and item menu actions
        spec.actions.forEach { action ->
            itemEl.getElemsBySelector("[ez-action='${action.id}']").onVanillaClick(true) {
                val result = action.onActivate.invoke(spec)
                if (result is EzCollComp.ResultUnmodified) {
                    return@onVanillaClick
                }
                val returnedItems = result.unsafeCast<EzCollComp.ResultModified<P>>().items
                // TODO: allow adding
                val returnedItem = returnedItems.getOrNull(0)
                if (returnedItem == null) {
                    onDelete(this)
                } else {
                    this.spec = returnedItem
                    rebuild()
                }
            }
        }

        // Init item menu
        if (spec.actions.isNotEmpty()) {
            Materialize.Dropdown.init(
                getElemById("ezc-item-action-menu-${spec.id}"),
                objOf("constrainWidth" to false, "coverTrigger" to false)
            )
        }
    }

    private fun initActionableAttrs() {
        val actionableAttrs = itemEl.getElemsBySelector("ezc-attr-text[class='actionable']")
        actionableAttrs.forEach { attrEl ->
            attrEl.onVanillaClick(false) {
                val attrId = attrEl.getAttribute("ez-attr-id") ?: return@onVanillaClick
                val allAttrs = spec.bottomAttrs + listOfNotNull(spec.topAttr)
                val chosenAttr = allAttrs.singleOrNull { it.id == attrId } ?: return@onVanillaClick
                val action = chosenAttr.onClick ?: return@onVanillaClick

                val result = action(spec)
                if (result is EzCollComp.ResultUnmodified) {
                    return@onVanillaClick
                }

                val returnedItem = result.unsafeCast<EzCollComp.ResultModified<P>>().items.getOrNull(0)
                // TODO: allow adding
                if (returnedItem == null) {
                    onDelete(this)
                } else {
                    this.spec = returnedItem
                    rebuild()
                }
            }
        }
    }

    private fun initSelection() {
        val checkbox = getElemByIdAs<HTMLInputElement>("ezc-item-checkbox-${spec.id}")
        checkbox.onVanillaClick(false) {
            isSelected = checkbox.checked
            updateSelectionState()
            onCheckboxClicked(this, isSelected)
        }
    }

    private fun updateSelectionState() {
        val checkbox = getElemByIdAs<HTMLInputElement>("ezc-item-checkbox-${spec.id}")
        if (checkbox.checked != isSelected) {
            checkbox.checked = isSelected
            checkbox.dispatchEvent(Event("change"))
        }

        if (isSelected) {
            itemEl.setAttribute("selected", "")
        } else {
            itemEl.removeAttribute("selected")
        }
    }
}
