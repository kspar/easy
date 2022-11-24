import libheaders.Mustache
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.toJsObj

fun tmRender(templateId: String, data: Map<String, Any?>? = null): String =
    render(getElemById(templateId).innerHTML, data ?: emptyMap())

fun tmRender(templateId: String, vararg dataPairs: Pair<String, Any?>): String =
    tmRender(templateId, mapOf(*dataPairs))

private fun render(template: String, data: Map<String, Any?>): String {
    return Mustache.render(template, data.toJsObj())
}

fun plainDstStr(vararg dstIds: String?): String = plainDstStr(dstIds.toList())

fun plainDstStr(dstIds: List<String?>): String =
    dstIds.filterNotNull().joinToString("\n") { """<ez-dst id="$it"></ez-dst>""" }
