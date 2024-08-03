package pages.course_exercise.teacher

import highlightCode
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template


class AdocCommentPreviewComp(
    parent: Component
) : Component(parent) {

    var contentHtml: String = ""
        set(value) {
            field = value
            rebuild()
            highlightCode()
        }

    override val children: List<Component>
        get() = listOf()

    override fun create() = doInPromise {

    }

    override fun render() = template(
        """
            <div style='padding: 1rem; background-color: #ccc'>
            {{{html}}}
            </div>            
        """.trimIndent(),
        "html" to contentHtml,
    )
}


