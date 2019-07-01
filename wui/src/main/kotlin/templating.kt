import org.w3c.dom.Element
import org.w3c.dom.HTMLScriptElement


fun tmRenderInto(templateId: String, data: Map<String, Any>, dst: Element) {
    dst.innerHTML = tmRender(templateId, data)
}

fun tmRender(templateId: String, data: Map<String, Any>): String =
        render(getElemByIdAs<HTMLScriptElement>(templateId).text, data)


private fun render(template: String, data: Map<String, Any>): String {
    Mustache.parse(template)
    return Mustache.render(template, dynamicToAny(data.toJsObj()))
}
