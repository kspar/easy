package pages.course_exercise.teacher

import highlightCode
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template


class SubmissionViewCommentComp(
    val grade: Int?,
    val commentHtml: String,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    private var commentGradeBadge: SubmissionCommentGradeComp? = null


    override val children: List<Component>
        get() = listOfNotNull(commentGradeBadge)

    override fun create() = doInPromise {
        commentGradeBadge = if (grade != null)
            SubmissionCommentGradeComp(grade, this)
        else null
    }

    override fun render() = template(
        """
            <div class='exercise-text'>
                {{{html}}}
            </div>
            ${commentGradeBadge.dstIfNotNull()}
        """.trimIndent(),
        "html" to commentHtml,
    )

    override fun postRender() {
        highlightCode()
    }
}
