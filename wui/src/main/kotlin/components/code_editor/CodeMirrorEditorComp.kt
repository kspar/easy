package components.code_editor

import libheaders.CodeMirrorInstance
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template

class CodeMirrorEditorComp(
    parent: Component?,
) : Component(parent) {

    private lateinit var editor: CodeMirrorInstance



    override val children: List<Component>
        get() = listOfNotNull()

    override fun create() = doInPromise {

    }

    override fun render() = template(
        """
            
        """.trimIndent(),

    )

    override fun postRender() {

    }
}