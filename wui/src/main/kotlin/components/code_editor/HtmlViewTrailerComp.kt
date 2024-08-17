package components.code_editor

import highlightCode
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import template

class HtmlViewEditorTrailerComp(
    private var html: String = "",
    parent: Component?,
) : Component(parent) {

    override fun render() = template(
        """
            <div style='min-height: 6.5rem; padding: 1rem; border-top: 1px solid var(--ez-border);'>
                <div class='exercise-text'>
                    {{{html}}}
                </div>
            </div>
        """.trimIndent(),
        "html" to html,
    )

    override fun postRender() {
        highlightCode()
    }

    suspend fun set(html: String) {
        this.html = html
        createAndBuild().await()
    }
}