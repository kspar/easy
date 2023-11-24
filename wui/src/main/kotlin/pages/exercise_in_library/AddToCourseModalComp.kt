package pages.exercise_in_library

import Icons
import components.ToastIds
import components.ToastThing
import components.form.RadioButtonsComp
import components.modal.BinaryModalComp
import components.text.StringComp
import dao.CoursesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.course_exercise.ExerciseSummaryPage
import rip.kspar.ezspa.*
import translation.Str

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
        Str.addToCourseModalTitle,
        Str.doAdd,
        Str.cancel,
        Str.adding,
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

    private fun singleExerciseText(title: String) =
        StringComp.boldTriple(Str.addToCourseModalText1, title, Str.addToCourseModalText2)

    private fun multiExerciseText(exerciseCount: Int) = StringComp.boldTriple(
        Str.addToCourseModalText1,
        exerciseCount.toString(),
        " ${Str.translateExercises(exerciseCount)}${Str.addToCourseModalText2}"
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
            val ceId = courseExIds.first()
            if (ceId != null)
                ToastThing(
                    Str.added,
                    ToastThing.Action(Str.goToIt, {
                        EzSpa.PageManager.navigateTo(ExerciseSummaryPage.link(courseId, ceId))
                    }),
                    id = ToastIds.exerciseAddedToCourse
                )
            else
                ToastThing(
                    Str.exerciseAlreadyOnCourse,
                    icon = ToastThing.ERROR,
                    id = ToastIds.exerciseAddedToCourse
                )
        } else {
            ToastThing(Str.added, id = ToastIds.exerciseAddedToCourse)
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

    override fun renderLoading() = Icons.spinner

    override fun postChildrenBuilt() {
        radioButtons.validateInitial()
    }

    fun getSelectedCourseId() = radioButtons.getSelectedOption()?.value
}