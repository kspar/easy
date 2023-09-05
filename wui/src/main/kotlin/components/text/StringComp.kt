package components.text

import rip.kspar.ezspa.Component
import template

class StringComp(
    var parts: List<Part>,
    parent: Component
) : Component(parent) {
    constructor(part: Part, parent: Component) : this(listOf(part), parent)
    constructor(text: String, parent: Component) : this(simpleText(text), parent)

    companion object {
        fun simpleText(text: String) = listOf(Part(text))
        fun boldTriple(prefix: String, bold: String, suffix: String) = listOf(
            Part(prefix),
            Part(bold, PartType.BOLD),
            Part(suffix),
        )
    }

    data class Part(val content: String, val type: PartType = PartType.NORMAL)
    enum class PartType { NORMAL, SEMIBOLD, BOLD }

    override fun render() = parts.joinToString("", "<p>", "</p>") {
        when (it.type) {
            PartType.NORMAL -> template(
                """{{txt}}""",
                "txt" to it.content
            )

            PartType.SEMIBOLD -> template(
                """<ez-string class="semibold">{{txt}}</ez-string>""".trimIndent(),
                "txt" to it.content
            )

            PartType.BOLD -> template(
                """<ez-string class="bold">{{txt}}</ez-string>""".trimIndent(),
                "txt" to it.content
            )
        }
    }
}
