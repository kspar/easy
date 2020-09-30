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
class AdocService {
    private val doctor = Asciidoctor.Factory.create()
    private val options = Options()

    init {
        val attributes = Attributes()
        attributes.setSourceHighlighter("highlightjs")

        // TODO: remove after all exercises have migrated to EasyCode
        attributes.setAttribute("run", "<span class=\"codehl run\">")
        attributes.setAttribute("nur", "</span>")
        attributes.setAttribute("in", "<span class=\"codehl input\">")
        attributes.setAttribute("ni", "</span>")
        attributes.setAttribute("nohl", "<span class=\"codehl nohl\">")
        attributes.setAttribute("lhon", "</span>")
        options.setAttributes(attributes)

        doctor.javaExtensionRegistry()
                .postprocessor(LinkExternaliserProcessor())
                .postprocessor(EasyCodeProcessor())
    }

    fun adocToHtml(content: String): String {
        return doctor.convert(content, options)
    }
}

class LinkExternaliserProcessor : Postprocessor() {
    override fun process(document: Document?, output: String?): String {
        val jdoc = Jsoup.parse(output, "UTF-8")
        jdoc.getElementsByTag("a").forEach {
            it.attr("target", "_blank")
                    .attr("rel", "noopener noreferrer")
        }
        return jdoc.body().html()
    }
}

class EasyCodeProcessor : Postprocessor() {
    private enum class CodeAttr(val id: String, val outputProducer: (String) -> (String)) {
        RUN("run", { "<span class=\"codehl run\">$it</span>" }),
        INPUT("in", { "<span class=\"codehl input\">$it</span>" }),
        NO_HIGHLIGHT("nohl", { "<span class=\"codehl nohl\">$it</span>" })
    }

    private val regex: Regex;

    init {
        val attrStr = CodeAttr.values().joinToString("|") { it.id }
        regex = Regex("\\\$($attrStr)\\[(.+?)(?<!\\\\)]", RegexOption.DOT_MATCHES_ALL)
    }

    override fun process(document: Document?, output: String): String {
        return output.replace(regex) {
            val attrTag = it.groupValues[1]
            val value = it.groupValues[2].replace("\\]", "]")

            val attr = CodeAttr.values().find { it.id == attrTag }

            if (attr == null) {
                log.error { "No attribute found for tag $attrTag" }
                it.value
            } else {
                attr.outputProducer.invoke(value)
            }
        }
    }
}
