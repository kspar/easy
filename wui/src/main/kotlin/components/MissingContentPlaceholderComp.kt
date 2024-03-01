package components

import hide
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.TextProp
import template

class MissingContentPlaceholderComp(
    text: String = "",
    private val startVisible: Boolean = false,
    parent: Component
) : Component(parent) {

    private val textProp = TextProp(text)
    var text: String by textProp

    override fun render() = template(
        """
            <div class="missing-content-wrap">
                <div class="antennaball"></div>
                <div class="antenna"></div>
                <div class="robot">
                    <div class="robot-eye looking"></div>
                    <div class="robot-eye looking"></div>
                </div>
                <p class='text'>$textProp</p>
            </div>
        """.trimIndent()
    )

    override fun postRender() {
        if (!startVisible)
            hide()
    }
}
