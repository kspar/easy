package components

import getContainer
import getElemsByClass
import libheaders.Materialize
import spa.Page
import kotlin.dom.clear

abstract class EasyPage : Page() {

    override fun clear() {
        super.clear()
        getContainer().clear()
    }

    override fun destruct() {
        super.destruct()

        // Destroy tooltips
        getElemsByClass("tooltipped")
                .map { Materialize.Tooltip.getInstance(it) }
                .forEach { it?.destroy() }

        // Dismiss toasts
        Materialize.Toast.dismissAll()
    }
}