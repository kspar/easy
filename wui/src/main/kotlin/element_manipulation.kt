import org.w3c.dom.*
import kotlin.browser.document


fun getContainer(): HTMLDivElement =
        getElemByIdAs("content-container")

fun getBody(): HTMLBodyElement =
        document.body as HTMLBodyElement

fun getNodelistBySelector(selector: String): NodeList =
        document.querySelectorAll(selector)

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

