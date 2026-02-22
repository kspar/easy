package core.ems.service

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.springframework.stereotype.Service


@Service
class MarkdownService {
    private val extensions = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
    )
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    fun mdToHtml(content: String): String {
        val document = parser.parse(content)
        val html = renderer.render(document)
        return externaliseLinks(html)
    }
}

private fun externaliseLinks(html: String): String {
    val jdoc = Jsoup.parse(html, "UTF-8")
    jdoc.getElementsByTag("a").forEach {
        it.attr("target", "_blank")
            .attr("rel", "noopener noreferrer")
    }
    return jdoc.body().html()
}
