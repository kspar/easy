package components.text

import rip.kspar.ezspa.Component

class ParagraphsComp(
    var paragraphs: List<String>,
    parent: Component
) : Component(parent) {

    override fun render() = paragraphs.joinToString(separator = "") { "<p>$it</p>" }
}