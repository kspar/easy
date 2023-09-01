package pages.exercise_in_library.editor.tsl

import Icons
import rip.kspar.ezspa.Component
import template

// TODO: replace with WarningComp
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
