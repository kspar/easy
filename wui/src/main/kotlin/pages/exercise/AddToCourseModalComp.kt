package pages.exercise

import Str
import components.StringComp
import components.form.RadioButtonsComp
import components.modal.BinaryModalComp
import dao.CoursesTeacherDAO
import dao.ExerciseDAO
import errorMessage
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class AddToCourseModalComp(
    private var exerciseIds: List<String>,
    // title is only used for a single exercise
    title: String,
    parent: Component,
) : Component(parent) {
    constructor(
        exerciseId: String,
        exerciseTitle: String,
        parent: Component
    ) : this(listOf(exerciseId), exerciseTitle, parent)

    private val modalComp: BinaryModalComp<Unit?> = BinaryModalComp(
        "Lisa ülesanne kursusele", Str.doAdd(), Str.cancel(), Str.adding(),
        primaryAction = { list.getSelectedCourseId()?.let { addToCourse(it) } },
        primaryPostAction = ::reinitialise,
        primaryButtonEnabledInitial = false, defaultReturnValue = null, fixFooter = true,
        parent = this
    )

    private lateinit var list: AddToCourseModalCoursesListComp
    private val text = StringComp(
        if (exerciseIds.size == 1)
            singleExerciseText(title)
        else
            multiExerciseText(exerciseIds.size),
        modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        list = AddToCourseModalCoursesListComp({ modalComp.primaryButton.setEnabled(it) }, modalComp)
        modalComp.setContentComps { listOf(text, list) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    fun setSingleExercise(id: String, title: String) {
        exerciseIds = listOf(id)
        setText(singleExerciseText(title))
    }

    fun setMultipleExercises(ids: List<String>) {
        exerciseIds = ids
        setText(multiExerciseText(ids.size))
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun singleExerciseText(title: String) = StringComp.boldTriple("Lisa ", title, " kursusele:")
    private fun multiExerciseText(exerciseCount: Int) = StringComp.boldTriple(
        "Lisa ",
        exerciseCount.toString(),
        " ${Str.translateExercises(exerciseCount)} kursusele:"
    )

    private fun setText(parts: List<StringComp.Part>) {
        text.parts = parts
        text.rebuild()
    }

    private fun reinitialise() {
        list.rebuild()
    }

    private suspend fun addToCourse(courseId: String) {
        val successes = exerciseIds.map {
            ExerciseDAO.addExerciseToCourse(it, courseId)
        }
        // show fail message only if one exercise was added
        if (successes.size == 1) {
            if (successes.first())
                successMessage { "Lisatud" }
            else
                errorMessage { "See ülesanne on juba kursusel olemas" }
        } else {
            successMessage { "Lisatud" }
        }
    }
}

class AddToCourseModalCoursesListComp(
    private val onValidChange: ((Boolean) -> Unit)? = null,
    parent: Component,
) : Component(parent) {

    private lateinit var radioButtons: RadioButtonsComp

    override val children: List<Component>
        get() = listOf(radioButtons)

    override fun create() = doInPromise {
        val courses = CoursesTeacherDAO.getMyCourses()

        val buttons = courses.map {
            RadioButtonsComp.Button(
                "${it.title} - ${it.student_count} ${Str.translateStudents(it.student_count)}",
                it.id
            )
        }

        radioButtons = RadioButtonsComp(
            buttons,
            onValidChange = onValidChange,
            parent = this,
        )
    }

    override fun render() = plainDstStr(radioButtons.dstId)

    override fun renderLoading() = "Laen kursuseid..."

    override fun postChildrenBuilt() {
        radioButtons.validateInitial()
    }

    fun getSelectedCourseId() = radioButtons.getSelectedOption()?.id
}