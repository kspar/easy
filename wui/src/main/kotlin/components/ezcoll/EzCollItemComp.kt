package components.ezcoll

import Icons
import components.form.CheckboxComp
import components.dropdown.DropdownIconMenuComp
import components.dropdown.DropdownMenuComp
import hide
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import rip.kspar.ezspa.*
import show
import style
import template
import translation.Str

class EzCollItemComp<P>(
    var spec: EzCollComp.Item<P>,
    val maxBottomAttrsCount: Int,
    var orderingIndex: Int,
    val compact: Boolean,
    private val onCheckboxClicked: (EzCollItemComp<P>, Boolean) -> Unit,
    private val onDelete: (EzCollItemComp<P>) -> Unit,
    parent: Component
) : Component(parent) {

    enum class Expandable { ALWAYS, WHEN_TOP_ATTR_HIDDEN, NEVER }

    private val expandable
        get() = when {
            spec.bottomAttrs.isNotEmpty() -> Expandable.ALWAYS
            spec.progressBar != null && spec.progressBar?.showAttr == true -> Expandable.ALWAYS
            spec.topAttr != null -> Expandable.WHEN_TOP_ATTR_HIDDEN
            else -> Expandable.NEVER
        }

    private val hasActions = spec.actions.isNotEmpty()

    private var isSelected: Boolean = false
    private var isExpanded: Boolean = false

    private var checkbox: CheckboxComp? = null
    private var actionMenu: DropdownIconMenuComp? = null

    override val children: List<Component>
        get() = listOfNotNull(checkbox, actionMenu)

    override fun create() = doInPromise {
        if (spec.isSelectable) {
            checkbox = CheckboxComp(
                onChange = {
                    isSelected = it == CheckboxComp.Value.CHECKED
                    updateSelectionState()
                    onCheckboxClicked(this, isSelected)
                },
                parent = this
            )
        }
        if (hasActions) {
            actionMenu = DropdownIconMenuComp(
                Icons.dotsVertical,
                Str.doEdit + "...",
                spec.actions.map { action ->
                    DropdownMenuComp.Item(
                        action.text, action.iconHtml,
                        onSelected = {
                            val result = action.onActivate.invoke(spec)
                            if (result is EzCollComp.ResultUnmodified) {
                                return@Item
                            }
                            val returnedItems = result.unsafeCast<EzCollComp.ResultModified<P>>().items
                            // Doesn't currently support adding items
                            val returnedItem = returnedItems.getOrNull(0)
                            if (returnedItem == null) {
                                onDelete(this)
                            } else {
                                this.spec = returnedItem
                                rebuild()
                            }
                            action.onResultModified?.invoke()
                        },
                        id = action.id
                    )
                },
                parent = this
            )
        }
    }

    override fun render() = template(
        """
            <ezc-item id="{{itemId}}" {{#isSelectable}}selectable{{/isSelectable}} class="{{#hasBottomAttrs}}two-rows{{/hasBottomAttrs}} {{#hasActions}}has-actions{{/hasActions}} {{#progressBar}}has-bar{{/progressBar}} {{#inactive}}inactive{{/inactive}} {{#compact}}compact{{/compact}}">
                <ezc-bar-container>
                    <ezc-item-container>
                        <ezc-left>
                            <ezc-item-type>
                                {{#isTypeIcon}}{{{typeHtml}}}{{/isTypeIcon}}
                                {{^isTypeIcon}}<ezc-item-type-text>{{{typeHtml}}}</ezc-item-type-text>{{/isTypeIcon}}
                            </ezc-item-type>
                            {{#isSelectable}}
                                <ezc-item-checkbox>$checkbox</ezc-item-checkbox>
                            {{/isSelectable}}
                        </ezc-left>
                        <ezc-main>
                            <ezc-center>
                                <ezc-first>
                                    <ezc-title>
                                        <a id='title-{{itemId}}' {{#titleInteraction}}href='{{href}}'{{/titleInteraction}}>{{title}}</a>
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
                                                    {{#green}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-green);"></ezc-progress-label-circle>{{green}} {{greenLabel}}</ez-progress-label>{{/green}}
                                                    {{#yellow}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-yellow);"></ezc-progress-label-circle>{{yellow}} {{yellowLabel}}</ez-progress-label>{{/yellow}}
                                                    {{#blue}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-blue);"></ezc-progress-label-circle>{{blue}} {{blueLabel}}</ez-progress-label>{{/blue}}
                                                    {{#grey}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-grey);"></ezc-progress-label-circle>{{grey}} {{greyLabel}}</ez-progress-label>{{/grey}}
                                                </ezc-attr-text></ezc-fold-value>
                                            </ezc-fold-attr>
                                        {{/showAttr}}{{/progressBar}}
                                    </ezc-fold>
                                </ezc-second>
                            </ezc-center>
                        </ezc-main>
                        {{#hasActions}}
                            <ezc-right>
                                $actionMenu
                            </ezc-right>
                        {{/hasActions}}
                    </ezc-item-container>
                    {{#progressBar}}
                        <ezc-progress-bar {{#showAttr}}title="{{labelValue}}"{{/showAttr}}>
                            <ezc-progress-bar-part style="background-color: var(--ez-dim-green); flex-grow: {{green}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ez-dim-yellow); flex-grow: {{yellow}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ez-dim-blue); flex-grow: {{blue}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ez-dim-grey); flex-grow: {{grey}};"></ezc-progress-bar-part>
                        </ezc-progress-bar>
                    {{/progressBar}}
                </ezc-bar-container>
                {{#isExpandable}}
                    <ezc-trailer {{#onlyForTopAttr}}ez-show-max="{{topAttrTrailerWidth}}"{{/onlyForTopAttr}}>
                        <ezc-expand-trailer>
                            <ez-icon-action ez-expand-item title="{{expandItemTitle}}" tabindex="0">{{{expandCaret}}}</ez-icon-action>
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
        "isExpandable" to (expandable != Expandable.NEVER),
        "onlyForTopAttr" to (expandable == Expandable.WHEN_TOP_ATTR_HIDDEN),
        "topAttrTrailerWidth" to spec.topAttr?.topAttrMinWidth?.maxShowSecondaryValuePx,
        "hasBottomAttrs" to spec.bottomAttrs.isNotEmpty(),
        "compact" to compact,
        "bottomAttrCount" to maxBottomAttrsCount,
        // This item is not expandable but others are
        "expandPlaceholder" to (expandable == Expandable.NEVER && maxBottomAttrsCount > 0),
        "attrWidthS" to spec.attrWidthS.valuePx,
        "attrWidthM" to spec.attrWidthM.valuePx,
        "hasGrowingAttrs" to if (spec.bottomAttrs.size == 1) true else spec.hasGrowingAttrs,
        "hasActions" to hasActions,
        "isTypeIcon" to spec.type.isIcon,
        "typeHtml" to spec.type.html,
        "title" to spec.title,
        "titleIcon" to spec.titleIcon?.icon,
        "titleIconLabel" to spec.titleIcon?.label,
        "titleInteraction" to spec.titleInteraction?.let {
            mapOf(
                "href" to when (it) {
                    is EzCollComp.TitleAction<*> -> "#!"
                    is EzCollComp.TitleLink -> it.href
                }
            )
        },
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
                        if (it.green > 0) add("${it.green} ${Str.completedLabel}")
                        if (it.yellow > 0) add("${it.yellow} ${Str.startedLabel}")
                        if (it.blue > 0) add("${it.blue} ${Str.ungradedLabel}")
                        if (it.grey > 0) add("${it.grey} ${Str.unstartedLabel}")
                    }.joinToString(" / "),
                    "greenLabel" to Str.completedLabel,
                    "yellowLabel" to Str.startedLabel,
                    "blueLabel" to Str.ungradedLabel,
                    "greyLabel" to Str.unstartedLabel,
                )
            else null
        },
        "expandCaret" to Icons.expandCaret,
        "expandItemTitle" to Str.doExpand,
    )

    public override fun postRender() {
        val titleInteraction = spec.titleInteraction
        if (titleInteraction != null && titleInteraction is EzCollComp.TitleAction<*>) {
            getElemById("title-${spec.id}").onVanillaClick(true) {
                titleInteraction.unsafeCast<EzCollComp.TitleAction<P>>().action.invoke(spec.props)
            }
        }

        if (expandable != Expandable.NEVER) {
            initExpanding()
            updateExpanded()
        }

        initActionableAttrs()
    }


    fun setSelected(selected: Boolean) {
        isSelected = selected
        updateSelectionState()
    }

    fun updateOrderingIndex() {
        rootElement.style = "order: $orderingIndex"
    }

    private fun initExpanding() {
        val expand = rootElement.getElemBySelector("ez-icon-action[ez-expand-item]")
        expand.onVanillaClick(false) {
            isExpanded = !isExpanded
            updateExpanded()
        }
    }

    private fun updateExpanded() {
        val fold = rootElement.getElemBySelector("ezc-fold")
        val trailer = rootElement.getElemBySelector("ezc-expand-trailer")
        val attrs = rootElement.getElemBySelector("ezc-bottom-attrs")
        if (isExpanded) {
            trailer.addClass("open")
            attrs.hide()
            fold.show()
        } else {
            trailer.removeClass("open")
            fold.hide()
            attrs.show()
        }
    }

    private fun initActionableAttrs() {
        val actionableAttrs = rootElement.getElemsBySelector("ezc-attr-text[class='actionable']")
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
                // Doesn't currently support adding items
                if (returnedItem == null) {
                    onDelete(this)
                } else {
                    this.spec = returnedItem
                    rebuild()
                }
            }
        }
    }

    private fun updateSelectionState() {
        checkbox?.value = if (isSelected) CheckboxComp.Value.CHECKED else CheckboxComp.Value.UNCHECKED

        if (isSelected) {
            rootElement.getElemBySelector("ezc-item").setAttribute("selected", "")
        } else {
            rootElement.getElemBySelector("ezc-item").removeAttribute("selected")
        }
    }
}