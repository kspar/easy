package rip.kspar.ezspa

import kotlinx.browser.document
import org.w3c.dom.*

fun getHtml(): HTMLHtmlElement =
    document.documentElement as HTMLHtmlElement

fun getBody(): HTMLBodyElement =
    document.body as HTMLBodyElement

fun getHeader(): Element =
    document.getElementsByTagName("header")[0] ?: throw ElementNotFoundException("No header element")

fun getMain(): Element =
    document.getElementsByTagName("main")[0] ?: throw ElementNotFoundException("No main element")

fun getFooter(): Element =
    document.getElementsByTagName("footer")[0] ?: throw ElementNotFoundException("No footer element")

fun getNodelistBySelector(selector: String): NodeList =
    document.querySelectorAll(selector)

fun getElemsBySelector(selector: String): List<Element> =
    getNodelistBySelector(selector).asList().mapNotNull { it as? Element }

fun Element.getElemsBySelector(selector: String): List<Element> =
    this.querySelectorAll(selector).asList().mapNotNull { it as? Element }

fun getElemBySelector(selector: String): Element? =
    document.querySelector(selector)

fun Element.getElemBySelector(selector: String): Element =
    getElemBySelectorOrNull(selector)
        ?: throw ElementNotFoundException("No element with selector '$selector' under ${this.localName}#${this.id}.${this.className}")

fun Element.getElemBySelectorOrNull(selector: String): Element? =
    this.querySelector(selector)

fun getElemsByClass(className: String): List<Element> =
    document.getElementsByClassName(className).asList()

fun Element.getElemsByClass(className: String): List<Element> =
    getElementsByClassName(className).asList()

fun getElemByIdOrNull(id: String): Element? =
    document.getElementById(id)

fun getElemById(id: String): Element =
    getElemByIdOrNull(id) ?: throw ElementNotFoundException("No element with id $id")

inline fun <reified T : Element> getElemByIdAsOrNull(id: String): T? =
    getElemByIdOrNull(id) as? T

inline fun <reified T : Element> getElemByIdAs(id: String): T =
    getElemByIdAsOrNull(id) ?: throw ElementNotFoundException("No element with id $id and type ${T::class.js.name}")


class ElementNotFoundException(msg: String) : RuntimeException(msg)
