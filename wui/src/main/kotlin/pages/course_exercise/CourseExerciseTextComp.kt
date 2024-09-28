package pages.course_exercise

import EzDate
import MathJax
import highlightCode
import rip.kspar.ezspa.Component
import template
import translation.Str


class CourseExerciseTextComp(
    val title: String,
    val textHtml: String?,
    val deadline: EzDate?,
    parent: Component
) : Component(parent) {

    override fun render() = template(
        """
            {{#deadline}}<p class="subheading-item"><span class="subheading">{{deadlineLabel}}:</span>{{deadline}}</p>{{/deadline}}
            <h2>{{title}}</h2>
            <div class="exercise-text">{{{text}}}</div>
        """.trimIndent(),
        "title" to title,
        "text" to textHtml,
        "deadline" to deadline?.toHumanString(EzDate.Format.FULL),
        "deadlineLabel" to Str.deadline,
    )

    override fun postRender() {
        highlightCode()
        MathJax.formatPageIfNeeded(textHtml.orEmpty())
    }
}
