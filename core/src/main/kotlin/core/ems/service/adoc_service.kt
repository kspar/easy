package core.ems.service

import org.asciidoctor.Asciidoctor
import org.asciidoctor.Attributes
import org.asciidoctor.Options
import org.springframework.stereotype.Service


// Annotated as service for automatic Spring initialization on start-up for fast first-time service access
@Service
class AsciiService {
    private val doctor = Asciidoctor.Factory.create()

    fun adocToHtml(content: String): String {
        val attributes = Attributes()
        attributes.setSourceHighlighter("highlightjs")

        // Custom easy attributes for code highlighting
        attributes.setAttribute("run", "<span class=\"codehl run\">")
        attributes.setAttribute("nur", "</span>")
        attributes.setAttribute("in", "<span class=\"codehl input\">")
        attributes.setAttribute("ni", "</span>")
        attributes.setAttribute("nohl", "<span class=\"codehl nohl\">")
        attributes.setAttribute("lhon", "</span>")

        val options = Options()
        options.setAttributes(attributes)

        return doctor.convert(content, options)
    }
}