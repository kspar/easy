import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import org.w3c.dom.Element
import org.w3c.dom.HTMLDivElement
import rip.kspar.ezspa.getElemByIdAs

const val CONTENT_CONTAINER_ID = "content-container"

fun getContainer(): HTMLDivElement =
        getElemByIdAs(CONTENT_CONTAINER_ID)

fun moveClass(allElements: List<Element>, selectedElement: Element, vararg classes: String) {
    partitionElementsWithAction(allElements, listOf(selectedElement),
            inactiveAction = {
                it.removeClass(*classes)
            },
            activeAction = {
                it.addClass(*classes)
            })
}

fun partitionElementsWithAction(allElements: List<Element>, selectedElements: List<Element>,
                                inactiveAction: (e: Element) -> Unit, activeAction: (e: Element) -> Unit) {
    allElements.forEach(inactiveAction)
    selectedElements.forEach(activeAction)
}

fun Element.hide() {
    this.addClass("display-none")
}

fun Element.show() {
    this.removeClass("display-none")
}
