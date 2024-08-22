package components.code_editor.parts

import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import template

class CodeEditorStatusComp(
    private var text: String = "",
    private var icon: String = "",
    parent: Component?,
) : Component(parent) {

    override fun render() = template(
        """
            <ez-flex class='icon-med'>
                <span style='padding-right: 1rem; color: var(--ez-icon-col); cursor: default;'>{{text}}</span>
                <ez-flex style='min-width: 2.4rem; justify-content: center;'>{{{icon}}}</ez-flex>
            </ez-flex>
        """.trimIndent(),
        "text" to text,
        "icon" to icon
    )

    suspend fun set(text: String? = null, icon: String? = null) {
        text?.let {
            this.text = it
        }
        icon?.let {
            this.icon = it
        }
        createAndBuild().await()
    }
}