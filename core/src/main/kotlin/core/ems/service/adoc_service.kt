package core.ems.service

import mu.KotlinLogging
import org.asciidoctor.Asciidoctor
import org.asciidoctor.Attributes
import org.asciidoctor.Options
import org.asciidoctor.ast.Document
import org.asciidoctor.extension.Postprocessor
import org.jsoup.Jsoup
import org.springframework.stereotype.Service


private val log = KotlinLogging.logger {}

// Annotated as service for automatic Spring initialization on start-up for fast first-time service access
@Service
class AsciiService {
    private val doctor = Asciidoctor.Factory.create()
    private val options = Options()

    init {
        val attributes = Attributes()
        attributes.setSourceHighlighter("highlightjs")

        // Custom easy attributes for code highlighting
        attributes.setAttribute("run", "<span class=\"codehl run\">")
        attributes.setAttribute("nur", "</span>")
        attributes.setAttribute("in", "<span class=\"codehl input\">")
        attributes.setAttribute("ni", "</span>")
        attributes.setAttribute("nohl", "<span class=\"codehl nohl\">")
        attributes.setAttribute("lhon", "</span>")
        options.setAttributes(attributes)

        doctor.javaExtensionRegistry().postprocessor(LinkExternaliserProcessor())
    }

    fun adocToHtml(content: String): String {
        return doctor.convert(content, options)
    }
}

class LinkExternaliserProcessor : Postprocessor() {
    override fun process(document: Document?, output: String?): String {
        val t0 = System.currentTimeMillis()

        val jdoc = Jsoup.parse(output, "UTF-8")
        jdoc.getElementsByTag("a").forEach {
            it.attr("target", "_blank").attr("rel", "noopener noreferrer")
        }
        val html = jdoc.body().html()

        log.debug { "Postprocessing took ${System.currentTimeMillis() - t0} ms" }
        return html
    }
}
