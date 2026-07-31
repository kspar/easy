package core.ems.service

import io.github.oshai.kotlinlogging.KotlinLogging
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
    private val options: Options

    init {
        // TODO: remove after all exercises have migrated to EasyCode
        val attributes = Attributes.builder().sourceHighlighter("highlightjs")
            .attribute("run", "<span class=\"codehl run\">")
            .attribute("nur", "</span>")
            .attribute("in", "<span class=\"codehl input\">")
            .attribute("ni", "</span>")
            .attribute("nohl", "<span class=\"codehl nohl\">")
            .attribute("lhon", "</span>")
            .build()

        options = Options.builder().attributes(attributes).build()

        doctor.javaExtensionRegistry()
            .postprocessor(LinkExternaliserProcessor())
            .postprocessor(EasyCodeProcessor())
    }

    fun adocToHtml(content: String): String {
        return doctor.convert(content, options)
    }
}

class LinkExternaliserProcessor : Postprocessor() {
    override fun process(document: Document?, output: String): String {
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
        val attrStr = CodeAttr.entries.joinToString("|") { it.id }
        regex = Regex("\\\$($attrStr)\\[(.+?)(?<!\\\\)]", RegexOption.DOT_MATCHES_ALL)
    }

    override fun process(document: Document?, output: String): String {
        return output.replace(regex) {
            val attrTag = it.groupValues[1]
            val value = it.groupValues[2].replace("\\]", "]")

            val attr = CodeAttr.entries.find { it.id == attrTag }

            if (attr == null) {
                log.error { "No attribute found for tag $attrTag" }
                it.value
            } else {
                attr.outputProducer.invoke(value)
            }
        }
    }
}
