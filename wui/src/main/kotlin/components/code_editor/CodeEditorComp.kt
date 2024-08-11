package components.code_editor

import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template

class CodeEditorComp(
    parent: Component?,
) : Component(parent) {


    override val children: List<Component>
        get() = listOfNotNull()

    override fun create() = doInPromise {

    }

    override fun render() = template(
        """
            <ez-code-editor>
                
            </ez-code-editor>    
        """.trimIndent(),

    )

    override fun postRender() {

    }
}