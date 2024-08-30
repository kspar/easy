package components.ezcoll

import Icons
import storage.LocalStore
import components.MissingContentPlaceholderComp
import components.chips.FilterChipComponent
import components.chips.FilterChipSetComp
import components.chips.FilterDropdownChipComp
import components.dropdown.DropdownButtonMenuSelectComp
import components.dropdown.DropdownIconMenuComp
import components.dropdown.DropdownMenuComp
import components.form.ButtonComp
import components.form.CheckboxComp
import components.form.IconButtonComp
import dao.ParticipantsDAO
import debug
import hide
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemBySelector
import show
import template
import translation.Str

class EzCollComp<P>(
    items: List<Item<P>>,
    private val strings: Strings,
    private val massActions: List<MassAction<P>> = emptyList(),
    private val filterGroups: List<FilterGroup<P>> = emptyList(),
    private val sorters: List<Sorter<P>> = emptyList(),
    private val compact: Boolean = false,
    userConf: EzCollConf.UserConf? = null,
    private val onConfChange: (suspend (EzCollConf.UserConf) -> Unit)? = null,
    parent: Component?,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    interface WithGroups {
        val groups: List<ParticipantsDAO.CourseGroup>
    }

    companion object {
        fun <T : WithGroups> createGroupFilter(
            groups: List<ParticipantsDAO.CourseGroup>,
            noGroupOption: Boolean = true
        ): FilterGroup<T>? =
            if (groups.isEmpty())
                null
            else
                FilterGroup(
                    Str.accountGroup,
                    buildList {
                        groups.map { g ->
                            add(Filter<T>(g.name, EzCollConf.TeacherSelectedGroupFilter(g.id)) {
                                it.props.groups.any { it.id == g.id }
                            })
                        }
                        if (noGroupOption)
                            add(Filter<T>(
                                Str.withoutAccountGroups,
                                EzCollConf.TeacherSelectedGroupFilter(LocalStore.TEACHER_SELECTED_GROUP_NONE_ID)
                            ) {
                                it.props.groups.isEmpty()
                            })
                    }
                )
    }

    data class Item<P>(
        val props: P,
        val type: ItemType,
        var title: String,
        val titleIcon: TitleIcon? = null,
        val titleStatus: TitleStatus = TitleStatus.NORMAL,
        val titleInteraction: TitleInteraction? = null,
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

    sealed interface TitleInteraction
    data class TitleAction<P>(val action: suspend (P) -> Unit) : TitleInteraction
    data class TitleLink(val href: String) : TitleInteraction

    data class Action<P>(
        val iconHtml: String,
        val text: String,
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

    data class Filter<P>(
        val label: String,
        val confType: EzCollConf.EzCollFilterType? = null,
        val id: String = IdGenerator.nextId(),
        val predicate: (Item<P>) -> Boolean
    )

    data class Sorter<P>(
        val label: String,
        val comparator: Comparator<Item<P>>,
        // if not provided, then the primary comparator's order is simply reversed
        val reverseComparator: Comparator<Item<P>>? = null,
        val confType: EzCollConf.EzCollSortType? = null,
        val id: String = IdGenerator.nextId()
    )

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

    data class Progress(val green: Int = 0, val yellow: Int = 0, val blue: Int = 0, val grey: Int = 0)
    data class ProgressBar(
        val green: Int = 0, val yellow: Int = 0, val blue: Int = 0, val grey: Int = 0,
        val showAttr: Boolean = false
    ) {
        constructor(progress: Progress, showAttr: Boolean = false) :
                this(progress.green, progress.yellow, progress.blue, progress.grey, showAttr)
    }

    enum class TitleStatus { NORMAL, INACTIVE }

    enum class CollMinWidth(val valuePx: String, val maxShowSecondaryValuePx: String) {
        W600("600", "599"),
        W400("400", "399"),
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

    // Currently applied filters in all filter groups
    var activeFilters: List<Filter<P>>
        private set

    // Currently applied sorter, if null then items are displayed in the created order
    var activeSorter: Sorter<P>?
        private set
    var sortOrderReversed: Boolean
        private set


    private lateinit var filterChips: FilterChipSetComp
    private var sortFieldBtn: DropdownButtonMenuSelectComp? = null
    private var sortOrderBtn: IconButtonComp? = null

    private var allCheckbox: CheckboxComp? = null
    private var massActionMenu: DropdownIconMenuComp? = null
    private var clearSelectionBtn: IconButtonComp? = null

    private lateinit var missingContentPlaceholder: MissingContentPlaceholderComp

    // Getters because items can change
    private val hasSelection: Boolean
        get() = items.any { it.spec.isSelectable }

    private val hasChangeableSorting: Boolean
        get() = sorters.size > 1 && items.isNotEmpty()

    init {
        val conf = userConf ?: EzCollConf.UserConf()

        activeFilters = filterGroups.mapNotNull {
            it.filters.firstOrNull {
                it.confType != null &&
                        // this filter in active normal filters?
                        (conf.filters.contains(it.confType) ||
                                // this filter is an active group filter?
                                conf.globalGroupFilter == it.confType)
            }
        }

        activeSorter =
            sorters.firstOrNull { it.confType != null && conf.sorter == it.confType } ?: sorters.firstOrNull()

        sortOrderReversed = conf.sortOrderReversed

        val specs = if (sorters.isNotEmpty()) {
            items.sortedWith(sorters.first().comparator)
        } else
            items

        val bottomAttrsCount = items.maxOfOrNull { it.bottomAttrs.size } ?: 0
        this.items = specs.mapIndexed { i, spec ->
            EzCollItemComp(spec, bottomAttrsCount, i, compact, ::itemSelectClicked, ::removeItem, this)
        }
    }


    override val children: List<Component>
        get() = items + listOfNotNull(
            filterChips, sortFieldBtn, sortOrderBtn,
            allCheckbox, massActionMenu, clearSelectionBtn,
            missingContentPlaceholder
        )

    override fun create() = doInPromise {
        filterChips = FilterChipSetComp(
            chips = filterGroups.map {
                FilterDropdownChipComp(
                    it.groupLabel,
                    it.filters.map {
                        FilterChipComponent.Filter(
                            it.label, icon = null,
                            selected = activeFilters.contains(it),
                            id = it.id
                        )
                    },
                    onChange = {
                        setActiveFilters()
                        onConfChange?.invoke(createActiveUserConf())
                    },
                )
            },
        )

        if (hasChangeableSorting) {
            sortFieldBtn = DropdownButtonMenuSelectComp(
                ButtonComp.Type.TEXT,
                items = sorters.map {
                    DropdownButtonMenuSelectComp.Item(
                        it.label,
                        selected = activeSorter == it,
                        id = it.id
                    )
                },
                onItemSelected = {
                    setActiveSorter(it.id)
                    onConfChange?.invoke(createActiveUserConf())
                },
                parent = this
            )

            sortOrderBtn = IconButtonComp(
                Icons.arrowUp, null,
                toggle = IconButtonComp.Toggle(
                    Icons.arrowDown,
                    startToggled = sortOrderReversed
                ),
                size = IconButtonComp.Size.SMALL,
                onClick = {
                    sortOrderReversed = sortOrderBtn!!.toggled
                    updateSorting()
                    onConfChange?.invoke(createActiveUserConf())
                },
                parent = this
            )
        }

        if (hasSelection) {
            allCheckbox = CheckboxComp(
                onChange = {
                    val isChecked = it == CheckboxComp.Value.CHECKED
                    val visibleSelectableItems = calcSelectableItems(true)

                    checkedItems = if (isChecked) visibleSelectableItems.toMutableList() else mutableListOf()
                    visibleSelectableItems.forEach { it.setSelected(isChecked) }
                    updateSelection()
                },
                parent = this
            )

            massActionMenu = DropdownIconMenuComp(
                Icons.dotsVertical, Str.ezcollApply,
                massActions.map {
                    DropdownMenuComp.Item(
                        it.text, it.iconHtml,
                        onSelected = { item ->
                            val action = massActions.single { it.id == item.id }
                            invokeMassAction(action)
                        },
                        id = it.id
                    )
                },
                parent = this
            )

            clearSelectionBtn = IconButtonComp(
                Icons.close, Str.ezcollClearSelection,
                size = IconButtonComp.Size.SMALL,
                onClick = { clearSelection() },
                parent = this
            )
        }

        missingContentPlaceholder = MissingContentPlaceholderComp(parent = this)
    }

    override fun render() = template(
        """
            <ez-coll-wrap {{#hasFiltering}}has-filtering{{/hasFiltering}} {{#hasSelection}}has-selection{{/hasSelection}}>
                <ezc-ctrl>
                    <ezc-ctrl-left>
                        {{#hasSelection}}
                            $allCheckbox
                        {{/hasSelection}}
                        <ezc-filters style='margin-left: .6rem;'>
                            $filterChips
                        </ezc-filters>
                        {{#hasSelection}}
                            $massActionMenu
                            <ezc-ctrl-selected></ezc-ctrl-selected>
                            $clearSelectionBtn
                        {{/hasSelection}}
                    </ezc-ctrl-left>
                    <ezc-ctrl-right>
                        {{#hasOrdering}}
                            $sortOrderBtn
                            $sortFieldBtn
                        {{/hasOrdering}}
                        <ezc-ctrl-shown style='margin-left: 2rem;'>
                            <ezc-ctrl-shown-count>{{{spinnerIcon}}}</ezc-ctrl-shown-count>
                            <ezc-ctrl-shown-name></ezc-ctrl-shown-name>
                        </ezc-ctrl-shown>
                    </ezc-ctrl-right>
                </ezc-ctrl>
                
                <ez-coll>
                    {{#items}}
                        <ez-dst id="{{dstId}}" style="order: {{idx}}"></ez-dst>
                    {{/items}}
                    
                    $missingContentPlaceholder
                </ez-coll>
            </ez-coll-wrap>
            """.trimIndent(),
        "hasFiltering" to filterGroups.isNotEmpty(),
        "hasSelection" to hasSelection,
        "hasOrdering" to hasChangeableSorting,
        "items" to items.map { mapOf("dstId" to it.dstId, "idx" to it.orderingIndex) },
        "sorters" to sorters.map {
            mapOf(
                "id" to it.id,
                "label" to it.label,
                "isSelected" to (it == activeSorter)
            )
        },
        "spinnerIcon" to Icons.spinner,
    )

    override fun postChildrenBuilt() {
        if (hasSelection) {
            updateSelection()
            selectItemsBasedOnChecked()
        }

        updateFiltering()
        // No need to update sorting, order styles are rendered into HTML

        if (items.isEmpty()) {
            missingContentPlaceholder.text = Str.ezcollEmpty
            missingContentPlaceholder.show()
        }
    }

    fun getOrderedVisibleItems(): List<Item<P>> =
        calcVisibleItems().sortedBy { it.orderingIndex }
            .map { it.spec }


    private fun calcVisibleItems(): List<EzCollItemComp<P>> = items.filter { item ->
        activeFilters.all {
            it.predicate(item.spec)
        }
    }

    private fun calcSelectableItems(onlyVisible: Boolean): List<EzCollItemComp<P>> {
        val selectable = items.filter { it.spec.isSelectable }
        return if (onlyVisible) {
            calcVisibleItems().intersect(selectable.toSet()).toList()
        } else
            selectable
    }

    private fun setActiveFilters() {
        val selectedFilterIds = filterChips.getActiveFilters().mapNotNull { it.value?.id }

        activeFilters = filterGroups.flatMap {
            it.filters
        }.filter {
            selectedFilterIds.contains(it.id)
        }

        updateFiltering()
    }

    private fun updateFiltering() {
        val isFilterActive = activeFilters.isNotEmpty()
        val visibleItems = calcVisibleItems()

        updateVisibleItems(visibleItems)
        updateShownCount(visibleItems.size, isFilterActive)
        updateCheckedItemsBasedOnVisible(visibleItems)
    }

    private fun updateVisibleItems(visibleItems: List<EzCollItemComp<P>>) {
        items.forEach { it.show(visibleItems.contains(it)) }

        if (visibleItems.isEmpty()) {
            missingContentPlaceholder.text = Str.ezcollNoMatchingItems
            missingContentPlaceholder.show()
        } else {
            missingContentPlaceholder.hide()
        }
    }

    private fun updateCheckedItemsBasedOnVisible(visibleItems: List<EzCollItemComp<P>>) {
        val (visibleCheckedItems, invisibleItems) = checkedItems.partition { visibleItems.contains(it) }
        checkedItems = visibleCheckedItems.toMutableList()
        invisibleItems.forEach { it.setSelected(false) }
        updateSelection()
    }

    private fun updateShownCount(visibleItemsCount: Int, isFilterActive: Boolean) {
        val totalItemsCount = items.size

        rootElement.let {
            if (isFilterActive) {
                it.getElemBySelector("ezc-ctrl-shown-count").textContent = "$visibleItemsCount / $totalItemsCount"
                it.getElemBySelector("ezc-ctrl-shown-name").textContent = Str.ezcollShown
            } else {
                it.getElemBySelector("ezc-ctrl-shown-count").textContent = totalItemsCount.toString()
                it.getElemBySelector("ezc-ctrl-shown-name").textContent =
                    if (totalItemsCount == 1) strings.totalItemsSingular else strings.totalItemsPlural
            }
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
                    item.compact,
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

    private fun clearSelection() {
        checkedItems.forEach { it.setSelected(false) }
        checkedItems.clear()
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

        updateMassActionVisibility()
        updateAllCheckbox()
    }

    private fun updateMassActionVisibility() {
        // update checked count
        val selectedCountEl = rootElement.getElemBySelector("ezc-ctrl-selected")
        if (checkedItems.isEmpty())
            selectedCountEl.textContent = ""
        else
            selectedCountEl.textContent = "${checkedItems.size} ${Str.ezcollSelected}"

        // update mass action and filter visibility
        if (checkedItems.isEmpty()) {
            massActionMenu!!.hide()
            clearSelectionBtn!!.hide()
            filterChips.show()
        } else {
            massActionMenu!!.show()
            clearSelectionBtn!!.show()
            filterChips.hide()
        }
    }


    private fun updateAllCheckbox() {
        allCheckbox?.value = when {
            // nothing
            checkedItems.isEmpty() -> CheckboxComp.Value.UNCHECKED
            // everything that can be selected
            checkedItems.size == calcSelectableItems(true).size -> CheckboxComp.Value.CHECKED
            // something in between
            else -> CheckboxComp.Value.INDETERMINATE
        }
    }

    private fun setActiveSorter(sorterId: String) {
        val selectedSorter = sorters.single { it.id == sorterId }
        activeSorter = selectedSorter

        // revert order back to normal
        sortOrderReversed = false
        sortOrderBtn!!.toggled = false

        updateSorting()
    }

    private fun updateSorting() {
        val currentSorter = activeSorter

        if (currentSorter != null) {
            val comparator =
                if (sortOrderReversed)
                    currentSorter.reverseComparator ?: currentSorter.comparator.reversed()
                else
                    currentSorter.comparator

            val compComparator = Comparator<EzCollItemComp<P>> { a, b -> comparator.compare(a.spec, b.spec) }

            items = items.sortedWith(compComparator)
            items.forEachIndexed { i, item ->
                item.orderingIndex = i
                item.updateOrderingIndex()
            }
        }
    }

    private fun createActiveUserConf(): EzCollConf.UserConf {
        val (groupFilters, filters) = activeFilters.mapNotNull { it.confType }
            .partition { it is EzCollConf.TeacherSelectedGroupFilter }
        val groupFilter = groupFilters.singleOrNull() as? EzCollConf.TeacherSelectedGroupFilter
        return EzCollConf.UserConf(
            filters = filters,
            globalGroupFilter = groupFilter,
            sorter = activeSorter?.confType,
            sortOrderReversed = sortOrderReversed,
        )
    }
}


