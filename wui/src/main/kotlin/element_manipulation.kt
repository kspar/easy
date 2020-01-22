import org.w3c.dom.*
import kotlin.browser.document
import kotlin.dom.addClass
import kotlin.dom.removeClass


fun getContainer(): HTMLDivElement =
        getElemByIdAs("content-container")

fun getBody(): HTMLBodyElement =
        document.body as HTMLBodyElement

fun getHeader(): Element =
        document.getElementsByTagName("header")[0] ?: throw RuntimeException("No header element")

fun getMain(): Element =
        document.getElementsByTagName("main")[0] ?: throw RuntimeException("No main element")

fun getNodelistBySelector(selector: String): NodeList =
        document.querySelectorAll(selector)

fun getElemsBySelector(selector: String): List<Element> =
        getNodelistBySelector(selector).asList().mapNotNull { it as? Element }

fun getElemBySelector(selector: String): Element? =
        document.querySelector(selector)

fun getElemsByClass(className: String): List<Element> =
        document.getElementsByClassName(className).asList()

fun getElemByIdOrNull(id: String): Element? =
        document.getElementById(id)

fun getElemById(id: String): Element =
        getElemByIdOrNull(id) ?: throw RuntimeException("No element with id $id")


inline fun <reified T : Element> getElemByIdAsOrNull(id: String): T? =
        getElemByIdOrNull(id) as? T

inline fun <reified T : Element> getElemByIdAs(id: String): T =
        getElemByIdAsOrNull(id)
                ?: throw RuntimeException("No element with id $id and type ${T::class.js.name}")


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