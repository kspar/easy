package pages.course_exercises_list

import EzDate
import Str
import components.form.DateTimeFieldComp
import components.form.RadioButtonsComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.text.AttrsComp
import dao.CourseExercisesTeacherDAO
import debug
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import show
import successMessage
import warn

class UpdateCourseExerciseModalComp(
    private val courseId: String,
    private val exercise: CourseExercise,
    // TODO: remove nullable when exercise summary page is migrated
    parent: Component?,
    dstId: String,
) : Component(parent, dstId) {

    data class CourseExercise(
        val id: String, val title: String, val alias: String?, val isVisible: Boolean, val visibleFrom: EzDate?
    )

    private val optionIdVisible = IdGenerator.nextId()
    private val optionIdHidden = IdGenerator.nextId()
    private val optionIdOpensLater = IdGenerator.nextId()

    private lateinit var title: AttrsComp
    private lateinit var aliasComp: StringFieldComp

    private lateinit var visibleRadio: RadioButtonsComp
    private lateinit var openingTime: DateTimeFieldComp

    private val modalComp: BinaryModalComp<Boolean?> = BinaryModalComp(
        "Ülesande sätted", Str.doSave(), Str.cancel(), Str.saving(), fixFooter = true,
        primaryAction = ::updateCourseExercise,
        primaryButtonEnabledInitial = false, defaultReturnValue = null, htmlClasses = "update-course-ex-title-modal",
        parent = this
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {

        title = AttrsComp(
            mapOf("Pealkiri ülesandekogus" to exercise.title),
            parent = modalComp
        )

        aliasComp = StringFieldComp(
            "Pealkiri kursusel", false,
            initialValue = exercise.alias ?: exercise.title,
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = { validate() },
            onENTER = { modalComp.primaryButton.click() },
            parent = modalComp
        )

        visibleRadio = RadioButtonsComp(
            listOf(
                RadioButtonsComp.Button(
                    "Nähtav",
                    type = if (exercise.isVisible) RadioButtonsComp.Type.PRESELECTED
                    else RadioButtonsComp.Type.SELECTABLE,
                    value = optionIdVisible
                ),
                RadioButtonsComp.Button(
                    "Peidetud",
                    type = if (exercise.visibleFrom == null) RadioButtonsComp.Type.PRESELECTED
                    else RadioButtonsComp.Type.SELECTABLE,
                    value = optionIdHidden
                ),
                RadioButtonsComp.Button(
                    "Muutub nähtavaks",
                    type = if (!exercise.isVisible && exercise.visibleFrom != null) RadioButtonsComp.Type.PRESELECTED
                    else RadioButtonsComp.Type.SELECTABLE,
                    value = optionIdOpensLater
                ),
            ),
            onValueChange = {
                openingTime.show(isOpensLaterSelected())
                validate()
            },
            parent = modalComp
        )

        openingTime = DateTimeFieldComp(
            "", true,
            notInPast = true,
            initialValue = if (!exercise.isVisible) exercise.visibleFrom else null,
            onValidChange = { validate() },
            fieldNameForMessage = "See väli",
            helpText = "Sel ajal muutub ülesanne automaatselt õpilastele nähtavaks",
            parent = modalComp
        )

        modalComp.setContentComps { listOf(title, aliasComp, visibleRadio, openingTime) }
    }

    override fun render() = ""

    override fun postChildrenBuilt() {
        aliasComp.validateInitial()
        visibleRadio.validateInitial()
        openingTime.validateInitial()

        openingTime.show(isOpensLaterSelected())
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun isVisibleSelected() = visibleRadio.getSelectedOption()?.value == optionIdVisible
    private fun isHiddenSelected() = visibleRadio.getSelectedOption()?.value == optionIdHidden
    private fun isOpensLaterSelected() = visibleRadio.getSelectedOption()?.value == optionIdOpensLater

    private fun validate() {
        val isValid = aliasComp.isValid
                && visibleRadio.isValid
                && if (isOpensLaterSelected()) openingTime.isValid else true

        modalComp.primaryButton.setEnabled(isValid)
    }

    private suspend fun updateCourseExercise(): Boolean {
        val alias = aliasComp.getValue()

        debug { "Updating course exercise ${exercise.id} title alias from ${exercise.alias} to $alias" }
        debug { "Alias: ${exercise.alias} -> $alias" }
        debug { "Is visible selected: ${isVisibleSelected()}" }
        debug { "Is hidden selected: ${isHiddenSelected()}" }
        debug { "Opens later selected: ${isOpensLaterSelected()}" }
        debug { "Visible from: ${exercise.visibleFrom} -> ${openingTime.getValue()}" }

        val update = CourseExercisesTeacherDAO.CourseExerciseUpdate(
            replace = CourseExercisesTeacherDAO.CourseExerciseReplace(
                titleAlias = if (alias.isNotBlank() && alias != exercise.title) alias else null,
                isStudentVisible = when {
                    isVisibleSelected() -> true
                    isHiddenSelected() -> false
                    else -> null
                },
                studentVisibleFrom = when {
                    isOpensLaterSelected() -> openingTime.getValue()
                        .also { if (it == null) warn { "Opens later selected but date is null" } }

                    else -> null
                }
            ),
            delete = buildSet {
                if (alias.isBlank() || alias == exercise.title)
                    add(CourseExercisesTeacherDAO.CourseExerciseDelete.TITLE_ALIAS)
            }
        )

        CourseExercisesTeacherDAO.updateCourseExercise(courseId, exercise.id, update).await()
        successMessage { "Muudetud" }
        return true
    }
}
