package pages.course_exercise.teacher

import rip.kspar.ezspa.Component
import template


class SubmissionCommentGradeComp(
    val grade: Int,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    override fun render() = template(
        """
            -- {{grade}} / 100 --
        """.trimIndent(),
        "grade" to grade.toString(),
    )
}
