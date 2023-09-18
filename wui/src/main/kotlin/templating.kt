import libheaders.Mustache
import org.intellij.lang.annotations.Language
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.toJsObj

// Get templates from DOM
fun tmRender(templateId: String, data: Map<String, Any?>? = null): String =
    render(getElemById(templateId).innerHTML, data ?: emptyMap())

fun tmRender(templateId: String, vararg dataPairs: Pair<String, Any?>): String =
    tmRender(templateId, mapOf(*dataPairs))

// Provide templates as string
fun template(@Language("handlebars") templateStr: String, data: Map<String, Any?>? = null): String =
    render(templateStr, data ?: emptyMap())

fun template(@Language("handlebars") templateStr: String, vararg dataPairs: Pair<String, Any?>): String =
    template(templateStr, mapOf(*dataPairs))

fun escapeHTML(html: String) = template("{{v}}", "v" to html)


private fun render(template: String, data: Map<String, Any?>): String {
    return Mustache.render(template, data.toJsObj())
}
