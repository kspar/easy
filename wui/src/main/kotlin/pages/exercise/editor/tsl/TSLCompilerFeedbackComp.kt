package pages.exercise.editor.tsl

import Icons
import rip.kspar.ezspa.Component
import template


class TSLCompilerFeedbackComp(
    private var feedback: String? = null,
    parent: Component
) : Component(parent) {

    override fun render() = template(
        """
            {{#feedback}}
                <ez-tsl-compiler-feedback>
                    {{{icon}}}
                    <pre id='{{id}}'>{{feedback}}</pre> 
                </ez-tsl-compiler-feedback>
            {{/feedback}}
        """.trimIndent(),
        "icon" to Icons.errorUnf,
        "feedback" to feedback,
    )

    fun setFeedback(feedback: String?) {
        this.feedback = feedback
        rebuild()
    }
}
