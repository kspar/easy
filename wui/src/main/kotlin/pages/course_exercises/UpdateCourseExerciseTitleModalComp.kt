package pages.course_exercises

import Str
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.text.AttrsComp
import dao.CourseExercisesTeacherDAO
import debug
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.sleep
import successMessage

class UpdateCourseExerciseTitleModalComp(
    private val courseId: String,
    parent: Component,
) : Component(parent) {

    data class CourseExercise(val id: String, val title: String, val alias: String?)

    var updatableCourseExercise: CourseExercise? = null

    private lateinit var title: AttrsComp
    private lateinit var aliasComp: StringFieldComp

    private val modalComp: BinaryModalComp<Boolean?> = BinaryModalComp(
        "Muuda pealkirja", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { updateAlias(aliasComp.getValue()) },
        // Not sure why pushing to event loop end is needed
        onOpen = { sleep(0).then { aliasComp.focus() } },
        primaryButtonEnabledInitial = false, defaultReturnValue = null, htmlClasses = "update-course-ex-title-modal",
        parent = this
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {

        title = AttrsComp(
            buildMap {
                updatableCourseExercise?.let {
                    set("Pealkiri ülesandekogus", it.title)
                }
            },
            parent = modalComp
        )

        aliasComp = StringFieldComp(
            "Pealkiri kursusel", false,
            initialValue = updatableCourseExercise?.alias.orEmpty(),
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = { modalComp.primaryButton.setEnabled(it) },
            onENTER = { modalComp.primaryButton.click() },
            parent = modalComp
        )

        modalComp.setContentComps { listOf(title, aliasComp) }
    }

    override fun render() = ""

    override fun postChildrenBuilt() {
        aliasComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private suspend fun updateAlias(newAlias: String): Boolean {
        val ex = updatableCourseExercise!!
        debug { "Updating course exercise ${ex.id} title alias from ${ex.alias} to $newAlias" }

        val update = if (newAlias.isEmpty()) {
            CourseExercisesTeacherDAO.CourseExerciseUpdate(
                delete = setOf(CourseExercisesTeacherDAO.CourseExerciseDelete.TITLE_ALIAS)
            )
        } else {
            CourseExercisesTeacherDAO.CourseExerciseUpdate(
                replace = CourseExercisesTeacherDAO.CourseExerciseReplace(titleAlias = newAlias)
            )
        }

        CourseExercisesTeacherDAO.updateCourseExercise(courseId, ex.id, update).await()
        successMessage { "Pealkiri muudetud" }
        return true
    }
}
