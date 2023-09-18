package components

import ifExistsStr
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import libheaders.Materialize
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import rip.kspar.ezspa.*
import template

class EzCollItemComp<P>(
    var spec: EzCollComp.Item<P>,
    val maxBottomAttrsCount: Int,
    var orderingIndex: Int,
    val compact: Boolean,
    private val onCheckboxClicked: (EzCollItemComp<P>, Boolean) -> Unit,
    private val onDelete: (EzCollItemComp<P>) -> Unit,
    parent: Component
) : Component(parent) {

    private val itemEl: Element
        get() = getElemById(spec.id)

    enum class Expandable { ALWAYS, WHEN_TOP_ATTR_HIDDEN, NEVER }

    private val expandable
        get() = when {
            spec.bottomAttrs.isNotEmpty() -> Expandable.ALWAYS
            spec.progressBar != null && spec.progressBar?.showAttr == true -> Expandable.ALWAYS
            spec.topAttr != null -> Expandable.WHEN_TOP_ATTR_HIDDEN
            else -> Expandable.NEVER
        }


    private var isSelected: Boolean = false

    private var isExpanded: Boolean = false

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
                            <label class="ezc-item-checkbox">
                                <input id="ezc-item-checkbox-{{itemId}}" type="checkbox" class="filled-in" /><span class="dummy"></span>
                            </label>
                        </ezc-left>
                        <ezc-main>
                            <ezc-center>
                                <ezc-first>
                                    <ezc-title>
                                        <a id='title-{{itemId}}' ${spec.titleAction.ifExistsStr { "href='#!'" }}>{{title}}</a>
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
                                                    {{#green}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-green);"></ezc-progress-label-circle>{{green}} lahendatud</ez-progress-label>{{/green}}
                                                    {{#yellow}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-yellow);"></ezc-progress-label-circle>{{yellow}} nässu läinud</ez-progress-label>{{/yellow}}
                                                    {{#blue}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-blue);"></ezc-progress-label-circle>{{blue}} hindamata</ez-progress-label>{{/blue}}
                                                    {{#grey}}<ez-progress-label><ezc-progress-label-circle style="background-color: var(--ez-grey);"></ezc-progress-label-circle>{{grey}} esitamata</ez-progress-label>{{/grey}}
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
                        <ezc-progress-bar {{#showAttr}}title="{{labelValue}}"{{/showAttr}}>
                            <ezc-progress-bar-part style="background-color: var(--ez-green); flex-grow: {{green}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ez-yellow); flex-grow: {{yellow}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ez-blue); flex-grow: {{blue}};"></ezc-progress-bar-part>
                            <ezc-progress-bar-part style="background-color: var(--ez-grey); flex-grow: {{grey}};"></ezc-progress-bar-part>
                        </ezc-progress-bar>
                    {{/progressBar}}
                </ezc-bar-container>
                {{#isExpandable}}
                    <ezc-trailer {{#onlyForTopAttr}}ez-show-max="{{topAttrTrailerWidth}}"{{/onlyForTopAttr}}>
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
        "hasActions" to spec.actions.isNotEmpty(),
        "isTypeIcon" to spec.type.isIcon,
        "typeHtml" to spec.type.html,
        "title" to spec.title,
        "titleIcon" to spec.titleIcon?.icon,
        "titleIconLabel" to spec.titleIcon?.label,
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
        val titleAction = spec.titleAction
        if (titleAction != null) {
            getElemById("title-${spec.id}").onVanillaClick(true) {
                titleAction.invoke(spec.props)
            }
        }

        if (expandable != Expandable.NEVER) {
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