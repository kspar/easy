package pages.exercise_library

import Str
import components.form.CheckboxComp
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class CreateExerciseModalComp(
    private val allowAddingToCourseId: String? = null,
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {


    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        "Uus ülesanne", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { createExercise(titleField.getValue()) },
        primaryPostAction = ::reinitialise, onOpen = { titleField.focus() },
        defaultReturnValue = null, parent = this
    )

    private val titleField = StringFieldComp(
        "Ülesande pealkiri",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 100)),
        onValidChange = ::updateSubmitBtn,
        parent = modalComp
    )

    // TODO: should save/remember addToCourse checkbox default value, requires EZ-1491
    private val addToCourseCheckbox: CheckboxComp? = if (allowAddingToCourseId != null)
        CheckboxComp("Lisa sellele kursusele", initialValue = true, parent = modalComp)
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

    private suspend fun createExercise(title: String): String {
        debug { "Saving new exercise with title $title" }
        val exerciseId = fetchEms("/exercises",
            ReqMethod.POST,
            mapOf(
                "title" to title,
                "public" to true,
                // TODO: should be TEACHER initially when we have the feature to change it afterwards
                "grader_type" to "AUTO",
                "grading_script" to "",
                "container_image" to "pygrader",
                "max_time_sec" to 7,
                "max_mem_mb" to 30,
                "assets" to emptyList<Map<String, String>>(),
            ),
            successChecker = { http200 }).await()
            .parseTo(NewExerciseDTO.serializer()).await().id
        debug { "Saved new exercise with id $exerciseId" }

        if (addToCourseCheckbox != null && addToCourseCheckbox.isChecked) {
            allowAddingToCourseId!!
            debug { "Adding exercise $exerciseId to course $allowAddingToCourseId" }

            fetchEms("/teacher/courses/$allowAddingToCourseId/exercises", ReqMethod.POST,
                mapOf(
                    "exercise_id" to exerciseId,
                    "threshold" to 100,
                    "student_visible" to false,
                    "assessments_student_visible" to true,
                ),
                successChecker = { http200 }
            ).await()
            debug { "Successfully added exercise $exerciseId to course $allowAddingToCourseId" }
        }

        return exerciseId
    }
}