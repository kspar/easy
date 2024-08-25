package pages.course_exercise.teacher

import rip.kspar.ezspa.Component
import template


class SubmissionCommentGradeComp(
    val grade: Int,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    override fun render() = template(
        """
            <ez-grade-badge style='margin-top: 1rem;'>
                {{grade}} / 100
            </ez-grade-badge>
        """.trimIndent(),
        "grade" to grade.toString(),
    )
}
