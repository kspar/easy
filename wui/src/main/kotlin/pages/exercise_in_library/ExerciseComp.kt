package pages.exercise_in_library

import EzDate
import MathJax
import components.UnorderedListComp
import dao.ExerciseDAO
import highlightCode
import lightboxExerciseImages
import pages.course_exercise.ExerciseSummaryPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import template
import translation.Str
import kotlin.js.Promise


class ExerciseComp(
    private val exercise: ExerciseDAO.Exercise,
    parent: Component?
) : Component(parent) {

    private lateinit var attributes: ExerciseAttributesComp
    private lateinit var textView: ExerciseTextComp

    override val children: List<Component>
        get() = listOf(attributes, textView)

    override fun create(): Promise<*> = doInPromise {
        attributes = ExerciseAttributesComp(exercise, this)
        textView = ExerciseTextComp(exercise.title, exercise.text_html.orEmpty(), this)
    }

    override fun render(): String = plainDstStr(attributes.dstId, textView.dstId)

    fun setText(textHtml: String) {
        textView.textHtml = textHtml
        textView.rebuild()
    }

    fun setTitle(title: String) {
        textView.title = title
        textView.rebuild()
    }
}


class ExerciseAttributesComp(
    private val exercise: ExerciseDAO.Exercise,
    parent: Component?
) : Component(parent) {

    private lateinit var onCoursesList: UnorderedListComp

    override val children: List<Component>
        get() = listOf(onCoursesList)

    override fun create() = doInPromise {
        onCoursesList = UnorderedListComp(
            exercise.on_courses.sortedByDescending { it.id.toInt() }.map {
                UnorderedListComp.Item(
                    it.effectiveTitle + (it.course_exercise_title_alias?.let { " ($it)" } ?: ""),
                    ExerciseSummaryPage.link(it.id, it.course_exercise_id)
                )
            } + if (exercise.on_courses_no_access > 0)
                listOf(UnorderedListComp.Item("+ ${exercise.on_courses_no_access} ${Str.hiddenCourses}"))
            else emptyList(),
            maxItemsToShow = 5,
            parent = this
        )
    }

    override fun render(): String = template(
        """
            <ez-attr><ez-attr-label>{{modifiedAtLabel}}:</ez-attr-label><span title='{{modifiedTitle}}'>{{modifiedAt}} · {{modifiedBy}}</span></ez-attr>
            <ez-attr><ez-attr-label>{{onCoursesLabel}}{{#onCoursesCount}} ({{onCoursesCount}}){{/onCoursesCount}}:</ez-attr-label>{{^onCoursesCount}}{{notUsedOnAnyCoursesLabel}}{{/onCoursesCount}}</ez-attr>
            <ez-dst class="attr-list" id="{{onCoursesListDst}}"></ez-dst>
        """.trimIndent(),
        "modifiedAtLabel" to Str.modifiedAt,
        "onCoursesLabel" to Str.usedOnCourses,
        "notUsedOnAnyCoursesLabel" to "-",
        "modifiedAt" to exercise.last_modified.toHumanString(EzDate.Format.DATE),
        "modifiedBy" to exercise.last_modified_by_id,
        "onCoursesListDst" to onCoursesList.dstId,
        "onCoursesCount" to (exercise.on_courses.size + exercise.on_courses_no_access),
        "modifiedTitle" to
                "${Str.modifiedAt} " +
                "${exercise.last_modified.toHumanString(EzDate.Format.FULL)} (${exercise.last_modified_by_id}), " +
                "${Str.exerciseCreatedAtPhrase} ${exercise.created_at.toHumanString(EzDate.Format.FULL)} (${exercise.owner_id})"
    )
}


class ExerciseTextComp(
    var title: String,
    var textHtml: String,
    parent: Component?
) : Component(parent) {

    override fun render() = template(
        """
            <h2 style="margin-top: 4rem;">{{title}}</h2>
            <div id="exercise-text">{{{html}}}</div>
        """.trimIndent(),
        "title" to title,
        "html" to textHtml,
    )

    override fun postRender() {
        highlightCode()
        MathJax.formatPageIfNeeded(textHtml)
        lightboxExerciseImages()
    }
}
