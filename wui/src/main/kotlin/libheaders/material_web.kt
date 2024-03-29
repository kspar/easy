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
//
//@JsModule("@material/web/button/filled-button.js")
//@JsNonModule
//external class MaterialFilledButton {
//    companion object
//    var disabled: Boolean
//}
//
//fun Element.MaterialFilledButton() = unsafeCast<MaterialFilledButton>()

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
}

fun mentionMaterialComponents() {
//     Used components need to be referenced here, so that they're not DCEd
    MdMenu
    MdMenuItem
    MdIconBtn
}

