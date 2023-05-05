package components

import Icons
import debug
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.clear
import kotlinx.dom.removeClass
import libheaders.Materialize
import libheaders.closePromise
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLOptionElement
import org.w3c.dom.events.Event
import rip.kspar.ezspa.*
import template
import tmRender
import kotlin.js.Promise

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
        val titleIcon: TitleIcon? = null,
        val titleStatus: TitleStatus = TitleStatus.NORMAL,
        val titleLink: String? = null,
        val topAttr: Attr<P>? = null,
        val bottomAttrs: List<Attr<P>> = emptyList(),
        val progressBar: ProgressBar? = null,
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

    data class TitleIcon(val icon: String, val label: String)

    data class Action<P>(
        val iconHtml: String,
        val text: String,
        val showShortcutIcon: Boolean = false,
        val shortcutMinCollWidth: CollMinWidth = CollMinWidth.W600,
        val id: String = IdGenerator.nextId(),
        val onResultModified: (suspend () -> Unit)? = null,
        val onActivate: suspend (Item<P>) -> Result,
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

    data class Filter<P>(val label: String, val id: String = IdGenerator.nextId(), val predicate: (Item<P>) -> Boolean)

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
            return if (items.isEmpty()) "--" else items.joinToString(separator) { it.longValue.toString() }
        }
    }

    data class ListAttrItem<ItemType : Any>(val shortValue: ItemType, val longValue: ItemType = shortValue)

    data class ProgressBar(
        val green: Int = 0, val yellow: Int = 0, val blue: Int = 0, val grey: Int = 0,
        val showAttr: Boolean = false
    )

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

    // Default sorter, used to detect when a non-default sorter is active
    private val defaultSorter: Sorter<P>? = if (useFirstSorterAsDefault) sorters.firstOrNull() else null

    // Currently applied sorter, if null then items are displayed in the created order
    private var activeSorter: Sorter<P>? = defaultSorter

    // Getters because items can change
    private val hasSelection: Boolean
        get() = items.any { it.spec.isSelectable }

    private val hasFiltering: Boolean
        get() = filterGroups.isNotEmpty() && items.isNotEmpty()

    private val hasChangeableSorting: Boolean
        get() = sorters.size > 1 && items.isNotEmpty()

    private val collId = IdGenerator.nextId()

    init {
        val specs = if (sorters.isNotEmpty() && useFirstSorterAsDefault) {
            items.sortedWith(sorters.first().comparator)
        } else
            items

        val bottomAttrsCount = items.maxOfOrNull { it.bottomAttrs.size } ?: 0
        this.items = specs.mapIndexed { i, spec ->
            EzCollItemComp(spec, bottomAttrsCount, i, ::itemSelectClicked, ::removeItem, this)
        }
    }


    override val children: List<Component>
        get() = items

    override fun render(): String {
        val activatedFilterIds = activatedFilters.flatMap { it.map { it.id } }

        return template(
            """
                <ez-coll-wrap id="{{collId}}" {{#hasSelection}}has-selection{{/hasSelection}}>
                    <ezc-ctrl>
                        <ezc-ctrl-left>
                            {{#hasSelection}}
                            <label class="ezc-all-checkbox">
                                <input id="ezc-select-all-{{collId}}" type="checkbox" class="filled-in" /><span class="dummy"></span>
                            </label>
                            <a class="btn-flat dropdown-trigger waves-effect disabled" data-target='ezc-select-action-dropdown-{{collId}}'>
                                <ezc-mass-action-btn-label>{{applyLabel}}{{{applyExpandIcon}}}</ezc-mass-action-btn-label>
                                <ezc-mass-action-btn-icon>{{{applyShortIcon}}}</ezc-mass-action-btn-icon>
                            </a>
                            <ezc-ctrl-selected></ezc-ctrl-selected>
                            {{/hasSelection}}
                        </ezc-ctrl-left>
                        <ezc-ctrl-right>
                            <ezc-ctrl-shown>
                                <ezc-ctrl-shown-icon></ezc-ctrl-shown-icon>
                                <ezc-ctrl-shown-count>
                                    <ez-spinner class="preloader-wrapper active">
                                        <div class="spinner-layer">
                                            <div class="circle-clipper left"><div class="circle"></div></div><div class="gap-patch"><div class="circle"></div></div><div class="circle-clipper right"><div class="circle"></div>
                                        </div>
                                        </div>
                                    </ez-spinner>
                                </ezc-ctrl-shown-count>
                                <ezc-ctrl-shown-name></ezc-ctrl-shown-name>
                            </ezc-ctrl-shown>
                            {{#hasFiltering}}
                            <ezc-ctrl-filter filter="off">
                                <ez-icon-action title="{{filterLabel}}" tabindex="0">
                                    <ez-icon class="filter-disabled-icon"><svg xmlns="http://www.w3.org/2000/svg" enable-background="new 0 0 24 24" height="24px" viewBox="0 0 24 24" width="24px" fill="#000000"><g><path d="M0,0h24 M24,24H0" fill="none"/><path d="M7,6h10l-5.01,6.3L7,6z M4.25,5.61C6.27,8.2,10,13,10,13v6c0,0.55,0.45,1,1,1h2c0.55,0,1-0.45,1-1v-6 c0,0,3.72-4.8,5.74-7.39C20.25,4.95,19.78,4,18.95,4H5.04C4.21,4,3.74,4.95,4.25,5.61z"/><path d="M0,0h24v24H0V0z" fill="none"/></g></svg></ez-icon>
                                </ez-icon-action>
                                <div class="input-field select-wrap">
                                    <select multiple>
                                        <optgroup label="{{removeFiltersLabel}}"></optgroup>
                                        {{#filterGroups}}
                                            <optgroup label="{{groupLabel}}">
                                                {{#filterOptions}}
                                                    <option value="{{value}}" {{#isSelected}}selected{{/isSelected}}>{{optionLabel}}</option>
                                                {{/filterOptions}}
                                            </optgroup>
                                        {{/filterGroups}}
                                    </select>
                                    <label></label>
                                </div>
                            </ezc-ctrl-filter>
                            {{/hasFiltering}}
                            {{#hasOrdering}}
                            <ezc-ctrl-order>
                                <ez-icon-action title="{{orderLabel}}" class="dropdown-trigger" data-target="ezc-sorting-dropdown-{{collId}}" tabindex="0">
                                    <ez-icon><svg xmlns="http://www.w3.org/2000/svg" height="18px" viewBox="0 0 24 24" width="18px" fill="#000000"><path d="M0 0h24v24H0V0z" fill="none"/><path d="M3 18h6v-2H3v2zM3 6v2h18V6H3zm0 7h12v-2H3v2z"/></svg></ez-icon>
                                </ez-icon-action>
                            </ezc-ctrl-order>
                            {{/hasOrdering}}
                        </ezc-ctrl-right>
                        <!-- Mass action menu structure -->
                        <ul id="ezc-select-action-dropdown-{{collId}}" class="dropdown-content">
                            {{#selectActions}}
                                <li><span ez-mass-action="{{id}}">{{{actionHtml}}}</span></li>
                            {{/selectActions}}
                        </ul>
                        <!-- Sorting menu structure -->
                        <ul id="ezc-sorting-dropdown-{{collId}}" class="dropdown-content">
                            {{#sorters}}
                                <li>
                                    <label>
                                        <input ez-sorter="{{id}}" name="sorter-{{collId}}" type="radio" {{#isSelected}}checked{{/isSelected}}/>
                                        <span>{{label}}</span>
                                    </label>
                                </li>
                            {{/sorters}}
                        </ul>
                    </ezc-ctrl>
                    <ez-coll {{#isEmpty}}empty{{/isEmpty}}>
                        {{#items}}
                            <ez-dst id="{{dstId}}" style="order: {{idx}}"></ez-dst>
                        {{/items}}
                        <ezc-empty-placeholder>{{{emptyPlaceholder}}}</ezc-empty-placeholder>
                        <ezc-no-match-placeholder>{{{noMatchingItemsPlaceholder}}}</ezc-no-match-placeholder>
                    </ez-coll>
                </ez-coll-wrap>
            """.trimIndent(),
            "collId" to collId,
            "hasSelection" to hasSelection,
            "hasFiltering" to hasFiltering,
            "hasOrdering" to hasChangeableSorting,
            "selectActions" to massActions.map { mapOf("actionHtml" to "${it.iconHtml} ${it.text}", "id" to it.id) },
            "items" to items.map { mapOf("dstId" to it.dstId, "idx" to it.orderingIndex) },
            "isEmpty" to items.isEmpty(),
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
            "emptyPlaceholder" to tmRender(
                "t-s-missing-content-wandering-eyes",
                "text" to "Siin pole veel midagi näidata."
            ),
            "noMatchingItemsPlaceholder" to tmRender(
                "t-s-missing-content-wandering-eyes",
                "text" to "Valitud filtritele ei vasta ükski rida."
            ),
        )
    }

    override fun postRender() {
        if (hasSelection)
            initSelection()
        if (hasFiltering)
            initFiltering()
        if (hasChangeableSorting)
            initSorting()
    }

    override fun postChildrenBuilt() {
        if (hasSelection)
            selectItemsBasedOnChecked()
        if (hasFiltering)
            updateFiltering()
        else
        // Still show total count if no filtering
            updateShownCount(items.size, false)

        // No need to update sorting, order styles are rendered into HTML
    }

    // FIXME: temporary optimisation
    override fun createAndBuild() = createAndBuild3() ?: Promise.Companion.resolve(Unit)

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

            val visibleSelectableItems = calcSelectableItems(true)

            checkedItems = if (isChecked) visibleSelectableItems.toMutableList() else mutableListOf()

            visibleSelectableItems.forEach { it.setSelected(isChecked) }
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

    private fun calcVisibleItems(): List<EzCollItemComp<P>> {
        return items.filter { item ->
            activatedFilters.all { filterGroup ->
                filterGroup.any { filter -> filter.predicate(item.spec) }
            }
        }
    }

    private fun calcSelectableItems(onlyVisible: Boolean): List<EzCollItemComp<P>> {
        val selectable = items.filter { it.spec.isSelectable }
        return if (onlyVisible) {
            calcVisibleItems().intersect(selectable.toSet()).toList()
        } else
            selectable
    }

    private fun updateFiltering() {
        if (!hasFiltering)
            return

        val isFilterActive = activatedFilters.isNotEmpty()
        val visibleItems = calcVisibleItems()

        updateVisibleItems(visibleItems)
        updateShownCount(visibleItems.size, isFilterActive)
        updateFilterIcon(isFilterActive)

        updateCheckedItemsBasedOnVisible(visibleItems)
    }

    private fun updateVisibleItems(visibleItems: List<EzCollItemComp<P>>) {
        items.forEach { it.setVisible(visibleItems.contains(it)) }

        if (visibleItems.isEmpty()) {
            getElemById(collId).getElemBySelector("ez-coll")
                .setAttribute("no-matched-items", "")
        } else {
            getElemById(collId).getElemBySelector("ez-coll")
                .removeAttribute("no-matched-items")
        }
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
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-count").textContent =
                "$visibleItemsCount / $totalItemsCount"
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-name").textContent = "kuvatud"
        } else {
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-icon").innerHTML = "Σ"
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-count").textContent = totalItemsCount.toString()
            getElemById(collId).getElemBySelector("ezc-ctrl-shown-name").textContent =
                if (totalItemsCount == 1) strings.totalItemsSingular else strings.totalItemsPlural
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
                    item.maxBottomAttrsCount,
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
        rebuild()
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
        rebuild()
    }

    private fun selectItemsBasedOnChecked() {
        val visibleItems = calcVisibleItems()
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
            // nothing
            checkedItems.isEmpty() -> false to false
            // everything that can be selected
            checkedItems.size == calcSelectableItems(true).size -> true to false
            // something in between
            else -> false to true
        }

        if (currentStatus != newStatus) {
            debug { "All checkbox status changed: $currentStatus -> $newStatus" }
            allCheckboxEl.checked = newStatus.first
            allCheckboxEl.indeterminate = newStatus.second
            allCheckboxEl.dispatchEvent(Event("change"))
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

        // Update icon
        getElemById(collId).getElemBySelector("ezc-ctrl-order")
            .setAttribute("custom-order", if (currentSorter != defaultSorter) "on" else "off")

        if (currentSorter != null) {
            // Order items
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
    val maxBottomAttrsCount: Int,
    var orderingIndex: Int,
    private val onCheckboxClicked: (EzCollItemComp<P>, Boolean) -> Unit,
    private val onDelete: (EzCollItemComp<P>) -> Unit,
    parent: Component
) : Component(parent) {

    private val itemEl: Element
        get() = getElemById(spec.id)

    private val isExpandable
        get() = spec.topAttr != null || spec.bottomAttrs.isNotEmpty() || spec.progressBar != null

    private var isSelected: Boolean = false

    private var isExpanded: Boolean = false

    override fun render() = template(
        """
            <ezc-item id="{{itemId}}" {{#isSelectable}}selectable{{/isSelectable}} class="{{#hasBottomAttrs}}two-rows{{/hasBottomAttrs}} {{#hasActions}}has-actions{{/hasActions}} {{#progressBar}}has-bar{{/progressBar}} {{#inactive}}inactive{{/inactive}}">
                <ezc-bar-container>
                    <ezc-item-container>
                        <ezc-left>
                            <ezc-item-type>
                                {{#isTypeIcon}}{{{typeHtml}}}{{/isTypeIcon}}
                                {{^isTypeIcon}}<ezc-item-type-text>{{{typeHtml}}}</ezc-item-type-text>{{/isTypeIcon}}
                            </ezc-item-type>
                            <label class="ezc-item-checkbox">
                                <input id="ezc-item-checkbox-{{itemId}}" type="checkbox" class="filled-in" /><span class="dummy"></span>
                            </label>
                        </ezc-left>
                        <ezc-main>
                            <ezc-center>
                                <ezc-first>
                                    <ezc-title>
                                        <a {{#titleLink}}href="{{titleLink}}"{{/titleLink}}>{{title}}</a>
                                        {{#titleIcon}}<ezc-title-icon title="{{titleIconLabel}}">{{{titleIcon}}}</ezc-title-icon>{{/titleIcon}}
                                    </ezc-title>
                                    {{#topAttr}}
                                        <ezc-attr class="top" ez-show-min="{{minCollWidth}}">
                                            <ezc-attr-text ez-attr-id="{{id}}" class="{{#isActionable}}actionable{{/isActionable}}" title="{{key}}: {{value}}">{{{shortValueHtml}}}</ezc-attr-text>
                                        </ezc-attr>
                                    {{/topAttr}}
                                </ezc-first>
                                <ezc-second>
                                        <ezc-bottom-attrs ez-attrs="{{bottomAttrCount}}" {{#hasGrowingAttrs}}ez-growing-attrs{{/hasGrowingAttrs}}>
                                            {{#bottomAttrs}}
                                                <ezc-attr class="bottom">
                                                    <ezc-attr-text ez-attr-id="{{id}}" class="{{#isActionable}}actionable{{/isActionable}}" title="{{key}}: {{value}}">
                                                        {{{shortValueHtml}}}
                                                    </ezc-attr-text>
                                                </ezc-attr>
                                            {{/bottomAttrs}}
                                        </ezc-bottom-attrs>
                                    <ezc-fold class="display-none">
                                        {{#topAttr}}
                                            <ezc-fold-attr ez-show-max="{{maxCollWidthInFold}}">
                                                <ezc-fold-key>{{key}}:</ezc-fold-key>
                                                <ezc-fold-value>
                                                    <ezc-attr-text ez-attr-id="{{id}}" class="{{#isActionable}}actionable{{/isActionable}}">{{value}}</ezc-attr-text>
                                                </ezc-fold-value>
                                            </ezc-fold-attr>
                                        {{/topAttr}}
                                        {{#bottomAttrs}}
                                            <ezc-fold-attr>
                                                <ezc-fold-key>{{key}}:</ezc-fold-key>
                                                <ezc-fold-value>
                                                    <ezc-attr-text ez-attr-id="{{id}}" class="{{#isActionable}}actionable{{/isActionable}}">{{value}}</ezc-attr-text>
                                                </ezc-fold-value>
                                            </ezc-fold-attr>
                                        {{/bottomAttrs}}
                                        {{#progressBar}}{{#showAttr}}
                                            <ezc-fold-attr>
                                                <ezc-fold-value><ezc-attr-text style='flex-wrap: wrap;'>
                                                    {{#green}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ezc-bar-green);"></ezc-progress-label-circle>{{green}} lahendatud</ez-progress-label>{{/green}}
                                                    {{#yellow}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ezc-bar-yellow);"></ezc-progress-label-circle>{{yellow}} nässu läinud</ez-progress-label>{{/yellow}}
                                                    {{#blue}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ezc-bar-blue);"></ezc-progress-label-circle>{{blue}} hindamata</ez-progress-label>{{/blue}}
                                                    {{#grey}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ezc-bar-grey);"></ezc-progress-label-circle>{{grey}} esitamata</ez-progress-label>{{/grey}}
                                                </ezc-attr-text></ezc-fold-value>
                                            </ezc-fold-attr>
                                        {{/showAttr}}{{/progressBar}}
                                    </ezc-fold>
                                    {{#hasActions}}
                                        <ezc-bottom-space>
                                            {{#actions}}{{#showShortcut}}
                                                <ez-icon-action ez-action="{{id}}" ez-show-min="{{minCollWidth}}" title="{{text}}" tabindex="0">{{{iconHtml}}}</ez-icon-action>
                                            {{/showShortcut}}{{/actions}}
                                        </ezc-bottom-space>
                                    {{/hasActions}}
                                </ezc-second>
                            </ezc-center>
                        </ezc-main>
                        {{#hasActions}}
                            <ezc-right>
                                <ez-icon-action id="ezc-item-action-menu-{{itemId}}" title="{{actionMenuTitle}}" class="dropdown-trigger icon-med" tabindex="0" data-target="ezc-item-action-dropdown-{{itemId}}">
                                    <ez-icon>
                                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="black" width="18px" height="18px"><path d="M0 0h24v24H0z" fill="none"/><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>
                                    </ez-icon>
                                </ez-icon-action>
                            </ezc-right>
                        {{/hasActions}}
                    </ezc-item-container>
                    {{#progressBar}}
                        <ezc-progress-bar title="{{labelValue}}">
                            <ezc-progress-bar-part style="background-color: var(--ezc-bar-green); flex-grow: {{green}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ezc-bar-yellow); flex-grow: {{yellow}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ezc-bar-blue); flex-grow: {{blue}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ezc-bar-grey); flex-grow: {{grey}};"></ezc-progress-bar-part>
                        </ezc-progress-bar>
                    {{/progressBar}}
                </ezc-bar-container>
                {{#isExpandable}}
                    <ezc-trailer>
                        <ezc-expand-trailer>
                            <ez-icon-action ez-expand-item title="{{expandItemTitle}}" tabindex="0">
                                <ez-icon>
                                    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="black" width="18px" height="18px"><path d="M0 0h24v24H0z" fill="none"/><path d="M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z"/></svg>
                                </ez-icon>
                            </ez-icon-action>
                        </ezc-expand-trailer>
                    </ezc-trailer>
                {{/isExpandable}}
                {{#expandPlaceholder}}
                    <ezc-trailer class="placeholder"></ezc-trailer>
                {{/expandPlaceholder}}
                <!-- Action menu structure -->
                <ul id="ezc-item-action-dropdown-{{itemId}}" class="dropdown-content">
                    {{#actions}}
                        <li><span ez-action="{{id}}">{{{iconHtml}}}{{text}}</span></li>
                    {{/actions}}
                </ul>
            </ezc-item>
        """.trimIndent(),
        "itemId" to spec.id,
        "isSelectable" to spec.isSelectable,
        "isExpandable" to isExpandable,
        "hasBottomAttrs" to spec.bottomAttrs.isNotEmpty(),
        "bottomAttrCount" to maxBottomAttrsCount,
        // This item is not expandable others are
        "expandPlaceholder" to (!isExpandable && maxBottomAttrsCount > 0),
        "attrWidthS" to spec.attrWidthS.valuePx,
        "attrWidthM" to spec.attrWidthM.valuePx,
        "hasGrowingAttrs" to if (spec.bottomAttrs.size == 1) true else spec.hasGrowingAttrs,
        "hasActions" to spec.actions.isNotEmpty(),
        "isTypeIcon" to spec.type.isIcon,
        "typeHtml" to spec.type.html,
        "title" to spec.title,
        "titleIcon" to spec.titleIcon?.icon,
        "titleIconLabel" to spec.titleIcon?.label,
        "titleLink" to spec.titleLink,
        "inactive" to (spec.titleStatus == EzCollComp.TitleStatus.INACTIVE),
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
        "progressBar" to spec.progressBar?.let {
            if (it.green + it.yellow + it.blue + it.grey > 0)
                objOf(
                    "green" to it.green,
                    "yellow" to it.yellow,
                    "blue" to it.blue,
                    "grey" to it.grey,
                    "showAttr" to it.showAttr,
                    "labelValue" to buildList {
                        if (it.green > 0) add("${it.green} lahendatud")
                        if (it.yellow > 0) add("${it.yellow} nässu läinud")
                        if (it.blue > 0) add("${it.blue} hindamata")
                        if (it.grey > 0) add("${it.grey} esitamata")
                    }.joinToString(" / ")
                )
            else null
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

    public override fun postRender() {
        if (isExpandable) {
            initExpanding()
            updateExpanded()
        }

        initActions()
        initActionableAttrs()

        if (spec.isSelectable) {
            initSelection()
            updateSelectionState()
        }
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
                action.onResultModified?.invoke()
            }
        }

        // Init item menu
        if (spec.actions.isNotEmpty()) {
            Materialize.Dropdown.init(
                getElemById("ezc-item-action-menu-${spec.id}"),
                objOf("constrainWidth" to false, "coverTrigger" to false, "container" to getBody())
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
