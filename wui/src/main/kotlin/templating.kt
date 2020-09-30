import rip.kspar.ezspa.getElemById

fun tmRender(templateId: String, data: Map<String, Any?>? = null): String =
        render(getElemById(templateId).innerHTML, data ?: emptyMap())

fun tmRender(templateId: String, vararg dataPairs: Pair<String, Any?>): String = tmRender(templateId, mapOf(*dataPairs))

fun plainDstStr(vararg dstIds: String): String {
    return dstIds.joinToString("\n") { """<ez-dst id="$it"></ez-dst>""" }
}

private fun render(template: String, data: Map<String, Any?>): String {
    Mustache.parse(template)
    return Mustache.render(template, dynamicToAny(data.toJsObj()))
}
