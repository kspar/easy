package pages.course_exercise

import EzDate
import MathJax
import dao.CourseExercisesStudentDAO
import highlightCode
import lightboxExerciseImages
import rip.kspar.ezspa.Component
import template
import translation.Str


class CourseExerciseTextComp(
    val courseExercise: CourseExercisesStudentDAO.ExerciseDetails,
    parent: Component
) : Component(parent) {

    override fun render() = template(
        """
            {{#deadline}}<p class="subheading-item"><span class="subheading">{{deadlineLabel}}:</span>{{deadline}}</p>{{/deadline}}
            <h2>{{title}}</h2>
            <div id="exercise-text">{{{text}}}</div>
        """.trimIndent(),
        "title" to courseExercise.effective_title,
        "text" to courseExercise.text_html,
        "deadline" to courseExercise.deadline?.toHumanString(EzDate.Format.FULL),
        "deadlineLabel" to Str.softDeadlineLabel,
    )

    override fun postRender() {
        lightboxExerciseImages()
        highlightCode()
        MathJax.formatPageIfNeeded(courseExercise.text_html.orEmpty())
    }
}
