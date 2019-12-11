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
        attributes.setAttribute("run", "pass:[<span class=\"codehl run\">]")
        attributes.setAttribute("nur", "pass:[</span>]")
        attributes.setAttribute("in", "pass:[<span class=\"codehl input\">]")
        attributes.setAttribute("ni", "pass:[</span>]")
        attributes.setAttribute("nohl", "pass:[</span class=\"codehl nohl\">]")
        attributes.setAttribute("lhon", "pass:[</span>]")

        val options = Options()
        options.setAttributes(attributes)

        return doctor.convert(content, options)
    }
}