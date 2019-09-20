import org.w3c.dom.HTMLScriptElement


fun tmRender(templateId: String, data: Map<String, Any?>? = null): String =
        render(getElemByIdAs<HTMLScriptElement>(templateId).text, data ?: emptyMap())


private fun render(template: String, data: Map<String, Any?>): String {
    Mustache.parse(template)
    return Mustache.render(template, dynamicToAny(data.toJsObj()))
}
