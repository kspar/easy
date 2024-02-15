package libheaders

import org.w3c.dom.Element

//import org.w3c.dom.Element
//
//@JsModule("@material/web/checkbox/checkbox.js")
//@JsNonModule
//external class MaterialCheckbox {
//    companion object
//    var checked: Boolean
//}
//
//fun Element.MaterialCheckbox() = unsafeCast<MaterialCheckbox>()

open external class MdButton {
    var disabled: Boolean
}

fun Element.MdButton() = unsafeCast<MdButton>()

@JsModule("@material/web/button/filled-button.js")
@JsNonModule
external class MdFilledButton : MdButton {
    companion object
}

@JsModule("@material/web/button/outlined-button.js")
@JsNonModule
external class MdOutlinedButton : MdButton {
    companion object
}

@JsModule("@material/web/button/elevated-button.js")
@JsNonModule
external class MdElevatedButton : MdButton {
    companion object
}

@JsModule("@material/web/button/filled-tonal-button.js")
@JsNonModule
external class MdTonalButton : MdButton {
    companion object
}

@JsModule("@material/web/button/text-button.js")
@JsNonModule
external class MdTextButton : MdButton {
    companion object
}


@JsModule("@material/web/menu/menu.js")
@JsNonModule
external class MdMenu {
    companion object

    var open: Boolean
}

fun Element.MdMenu() = unsafeCast<MdMenu>()

@JsModule("@material/web/menu/menu-item.js")
@JsNonModule
external class MdMenuItem {
    companion object
}

@JsModule("@material/web/iconbutton/icon-button.js")
@JsNonModule
external class MdIconBtn {
    companion object

    var disabled: Boolean
}

fun Element.MdIconBtn() = unsafeCast<MdIconBtn>()

@JsModule("@material/web/tabs/tabs.js")
@JsNonModule
external class MdTabs {
    companion object

    var activeTabIndex: Int
}

fun Element.MdTabs() = unsafeCast<MdTabs>()

external interface MdTab

@JsModule("@material/web/tabs/primary-tab.js")
@JsNonModule
external class MdPrimaryTab : MdTab {
    companion object
}

@JsModule("@material/web/tabs/secondary-tab.js")
@JsNonModule
external class MdSecondaryTab : MdTab {
    companion object
}


fun mentionMaterialComponents() {
//     Used components need to be referenced here, so that they're not DCEd
    MdFilledButton
    MdOutlinedButton
    MdElevatedButton
    MdTonalButton
    MdTextButton

    MdMenu
    MdMenuItem
    MdIconBtn
    MdTabs
    MdPrimaryTab
    MdSecondaryTab
}

