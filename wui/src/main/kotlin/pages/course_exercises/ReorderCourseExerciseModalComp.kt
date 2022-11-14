package pages.course_exercises

import Str
import components.StringComp
import components.form.RadioButtonsComp
import components.modal.BinaryModalComp
import components.modal.Modal
import dao.CourseExercisesTeacherDAO
import debug
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class ReorderCourseExerciseModalComp(
    private val courseId: String,
    parent: Component,
) : Component(parent) {

    data class CourseExercise(val id: String, val title: String, val idx: Int)

    var movableExercise: CourseExercise? = null
    var allExercises: List<CourseExercise> = emptyList()

    private var text: StringComp = StringComp("", this)
    private lateinit var exercisesRadioBtns: RadioButtonsComp

    private val modalComp: BinaryModalComp<Unit?> = BinaryModalComp(
        null, "Liiguta", Str.cancel(), "Liigutan...",
        primaryAction = { exercisesRadioBtns.getSelectedOption()?.id?.let { moveExercise(it.toInt()) } },
        primaryButtonEnabledInitial = false, defaultReturnValue = null, fixFooter = true,
        id = Modal.REORDER_COURSE_EXERCISES, parent = this
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        val ex = movableExercise

        val buttons = if (ex != null) {

            val oldIdx = ex.idx

            val firstBtn = RadioButtonsComp.Button(
                "", "0",
                if (oldIdx == 0) RadioButtonsComp.Type.DISABLED else RadioButtonsComp.Type.SELECTABLE
            )

            val otherBtns = (allExercises - ex).mapIndexed { i, e ->
                RadioButtonsComp.Button(
                    e.title, (i + 1).toString(),
                    if (oldIdx == e.idx + 1) RadioButtonsComp.Type.DISABLED else RadioButtonsComp.Type.SELECTABLE
                )
            }

            listOf(firstBtn) + otherBtns
        } else emptyList()

        exercisesRadioBtns = RadioButtonsComp(
            buttons, selectLineAfterButtons = true,
            onValidChange = { modalComp.primaryButton.setEnabled(it) },
            parent = modalComp
        )

        modalComp.setContentComps { listOf(text, exercisesRadioBtns) }
    }

    override fun render() = ""

    override fun postChildrenBuilt() {
        exercisesRadioBtns.validateInitial()
    }

    fun setText(parts: List<StringComp.Part>) {
        text.parts = parts
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private suspend fun moveExercise(newIdx: Int) {
        val movableId = movableExercise!!.id
        debug { "Move ex $movableId to idx $newIdx" }
        CourseExercisesTeacherDAO.reorderCourseExercise(courseId, movableId, newIdx).await()
        successMessage { "Liigutatud" }
    }
}
