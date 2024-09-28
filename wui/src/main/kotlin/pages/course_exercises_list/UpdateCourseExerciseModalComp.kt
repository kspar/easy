package pages.course_exercises_list

import EzDate
import components.form.DateTimeFieldComp
import components.form.IntFieldComp
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
import translation.Str
import warn

class UpdateCourseExerciseModalComp(
    private val courseId: String,
    private val exercise: CourseExercise,
    parent: Component,
    dstId: String = IdGenerator.nextId(),
) : Component(parent, dstId) {

    data class CourseExercise(
        val id: String, val title: String, val alias: String?, val threshold: Int,
        val isVisible: Boolean, val visibleFrom: EzDate?,
        val softDeadline: EzDate?, val hardDeadline: EzDate?,
    )

    private val optionIdVisible = IdGenerator.nextId()
    private val optionIdHidden = IdGenerator.nextId()
    private val optionIdOpensLater = IdGenerator.nextId()

    private lateinit var title: AttrsComp
    private lateinit var aliasComp: StringFieldComp

    private lateinit var visibleRadio: RadioButtonsComp
    private lateinit var openingTime: DateTimeFieldComp

    private lateinit var softDeadline: DateTimeFieldComp
    private lateinit var hardDeadline: DateTimeFieldComp

    private lateinit var threshold: IntFieldComp

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        Str.exerciseSettings, Str.doSave, Str.cancel, Str.saving, fixFooter = true,
        primaryAction = ::updateCourseExercise,
        primaryButtonEnabledInitial = false, defaultReturnValue = false, htmlClasses = "update-course-ex-title-modal",
        parent = this
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {

        title = AttrsComp(
            mapOf(Str.titleInLib to exercise.title),
            parent = modalComp
        )

        aliasComp = StringFieldComp(
            Str.titleOnCourse, false,
            initialValue = exercise.alias ?: exercise.title,
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = { validate() },
            onENTER = { modalComp.primaryButton.click() },
            parent = modalComp
        )

        visibleRadio = RadioButtonsComp(
            listOf(
                RadioButtonsComp.Button(
                    Str.visible,
                    type = if (exercise.isVisible) RadioButtonsComp.Type.PRESELECTED
                    else RadioButtonsComp.Type.SELECTABLE,
                    value = optionIdVisible
                ),
                RadioButtonsComp.Button(
                    Str.hidden,
                    type = if (exercise.visibleFrom == null) RadioButtonsComp.Type.PRESELECTED
                    else RadioButtonsComp.Type.SELECTABLE,
                    value = optionIdHidden
                ),
                RadioButtonsComp.Button(
                    Str.visibleFrom,
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
            fieldNameForMessage = Str.fieldNameThisField,
            helpText = Str.visibleFromHelp,
            parent = modalComp
        )

        softDeadline = DateTimeFieldComp(
            Str.deadline, false,
            initialValue = exercise.softDeadline,
            helpText = Str.deadlineHelp,
            htmlClasses = "update-course-exercise-deadline",
            onENTER = { modalComp.primaryButton.click() },
            parent = this
        )

        hardDeadline = DateTimeFieldComp(
            Str.closingTime, false,
            initialValue = exercise.hardDeadline,
            helpText = Str.closingTimeHelp,
            htmlClasses = "update-course-exercise-closing",
            onENTER = { modalComp.primaryButton.click() },
            parent = this
        )

        threshold = IntFieldComp(
            Str.threshold, true, minValue = 0, maxValue = 100,
            initialValue = exercise.threshold,
            helpText = Str.thresholdHelp,
            htmlClasses = "update-course-exercise-threshold",
            onValidChange = { validate() },
            onENTER = { modalComp.primaryButton.click() },
            parent = this
        )

        modalComp.setContentComps {
            listOf(title, aliasComp, visibleRadio, openingTime, softDeadline, hardDeadline, threshold)
        }
    }

    override fun render() = ""

    override fun postChildrenBuilt() {
        aliasComp.validateInitial()
        visibleRadio.validateInitial()
        openingTime.validateInitial()
        threshold.validateInitial()

        openingTime.show(isOpensLaterSelected())
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun isVisibleSelected() = visibleRadio.getSelectedOption()?.value == optionIdVisible
    private fun isHiddenSelected() = visibleRadio.getSelectedOption()?.value == optionIdHidden
    private fun isOpensLaterSelected() = visibleRadio.getSelectedOption()?.value == optionIdOpensLater

    private fun validate() {
        val isValid = aliasComp.isValid
                && visibleRadio.isValid
                && threshold.isValid
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
        debug { "Deadline: ${softDeadline.getValue()}" }
        debug { "Closing time: ${hardDeadline.getValue()}" }
        debug { "Threshold: ${threshold.getIntValue()}" }

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
                },
                softDeadline = softDeadline.getValue(),
                hardDeadline = hardDeadline.getValue(),
                threshold = threshold.getIntValue(),
            ),
            delete = buildSet {
                if (alias.isBlank() || alias == exercise.title)
                    add(CourseExercisesTeacherDAO.CourseExerciseDelete.TITLE_ALIAS)
                if (softDeadline.getValue() == null)
                    add(CourseExercisesTeacherDAO.CourseExerciseDelete.SOFT_DEADLINE)
                if (hardDeadline.getValue() == null)
                    add(CourseExercisesTeacherDAO.CourseExerciseDelete.HARD_DEADLINE)
            }
        )

        CourseExercisesTeacherDAO.updateCourseExercise(courseId, exercise.id, update).await()
        successMessage { Str.changed }
        return true
    }

    // Not sure what modal child component is causing unsaved changes but probably due to some dynamic update here
    override fun hasUnsavedChanges() = false
}
