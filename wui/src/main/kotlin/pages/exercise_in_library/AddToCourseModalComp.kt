package pages.exercise_in_library

import Str
import components.text.StringComp
import components.form.RadioButtonsComp
import components.modal.BinaryModalComp
import dao.CoursesTeacherDAO
import dao.ExerciseDAO
import errorMessage
import kotlinx.coroutines.await
import rip.kspar.ezspa.plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.unionPromise
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
        "Lisa ülesanne kursusele",
        Str.doAdd(),
        Str.cancel(),
        Str.adding(),
        defaultReturnValue = null,
        primaryButtonEnabledInitial = false,
        fixFooter = true,
        primaryAction = { list.getSelectedCourseId()?.let { addToCourse(it) } },
        primaryPostAction = ::reinitialise,
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
        val courseExIds = exerciseIds.map {
            ExerciseDAO.addExerciseToCourse(it, courseId)
        }.unionPromise().await()
        // show fail message only if one exercise was added
        if (courseExIds.size == 1) {
            if (courseExIds.first() != null)
                successMessage { "Lisatud" }
            else
                errorMessage { "See ülesanne on kursusel juba olemas" }
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
        val courses = CoursesTeacherDAO.getMyCourses().await()

        val buttons = courses.map {
            RadioButtonsComp.Button(
                "${it.effectiveTitle} - ${it.student_count} ${Str.translateStudents(it.student_count)}",
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

    fun getSelectedCourseId() = radioButtons.getSelectedOption()?.value
}