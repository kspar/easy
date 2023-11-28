package pages.exercise_library

import components.form.CheckboxComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import dao.ExerciseDAO
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import translation.Str

class CreateExerciseModalComp(
    private val dirId: String?,
    private val allowAddingToCourseId: String?,
    parent: Component,
) : Component(parent) {

    data class ExIds(val exerciseId: String, val courseExerciseId: String?)

    private val modalComp: BinaryModalComp<ExIds?> = BinaryModalComp(
        Str.newExercise, Str.doSave, Str.cancel, Str.saving,
        defaultReturnValue = null,
        primaryAction = { createExercise(titleField.getValue()) }, primaryPostAction = ::reinitialise,
        onOpen = { titleField.focus() }, parent = this
    )

    private val titleField = StringFieldComp(
        Str.exerciseTitle,
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 100)),
        onValidChange = ::updateSubmitBtn,
        onENTER = { modalComp.primaryButton.click() },
        parent = modalComp
    )

    // TODO: should save/remember addToCourse checkbox default value, requires EZ-1491
    private val addToCourseCheckbox: CheckboxComp? = if (allowAddingToCourseId != null)
        CheckboxComp(Str.addToThisCourse, initialValue = true, parent = modalComp)
    else null

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOfNotNull(titleField, addToCourseCheckbox) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        titleField.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        titleField.rebuild()
        titleField.validateInitial()
        addToCourseCheckbox?.rebuild()
    }

    private fun updateSubmitBtn(isTitleValid: Boolean) {
        modalComp.primaryButton.setEnabled(isTitleValid)
    }


    @Serializable
    private data class NewExerciseDTO(val id: String)

    private suspend fun createExercise(title: String): ExIds {
        debug { "Saving new exercise with title $title" }
        val exerciseId = fetchEms("/exercises",
            ReqMethod.POST,
            mapOf(
                "parent_dir_id" to dirId,
                "title" to title,
                "public" to true,
                "grader_type" to "TEACHER",
                "anonymous_autoassess_enabled" to false,
            ),
            successChecker = { http200 }).await()
            .parseTo(NewExerciseDTO.serializer()).await().id

        debug { "Saved new exercise with id $exerciseId" }

        val courseExId = if (addToCourseCheckbox != null && addToCourseCheckbox.isChecked) {
            allowAddingToCourseId!!
            ExerciseDAO.addExerciseToCourse(exerciseId, allowAddingToCourseId).await()
        } else null

        return ExIds(exerciseId, courseExId)
    }
}