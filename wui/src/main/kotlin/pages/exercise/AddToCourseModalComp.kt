package pages.exercise

import Str
import components.StringComp
import components.form.RadioButtonsComp
import components.modal.BinaryModalComp
import dao.CoursesTeacher
import debug
import kotlinx.coroutines.await
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class AddToCourseModalComp(
    private val exerciseId: String,
    private val exerciseTitle: String,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Unit?> = BinaryModalComp(
        "Lisa ülesanne kursusele", Str.doAdd(), Str.cancel(), Str.adding(),
        primaryAction = { list.getSelectedCourseId()?.let { addToCourse(it) } },
        primaryPostAction = ::reinitialise,
        primaryButtonEnabledInitial = false, defaultReturnValue = null, fixFooter = true,
        parent = this
    )

    private lateinit var list: AddToCourseModalCoursesListComp
    private val text = StringComp(
        StringComp.boldTriple("Lisa ", exerciseTitle, " kursusele:"),
        modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        list = AddToCourseModalCoursesListComp({ modalComp.primaryButton.setEnabled(it) }, modalComp)
        modalComp.setContentComps { listOf(text, list) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        list.rebuild()
    }

    private suspend fun addToCourse(courseId: String) {
        debug { "Adding exercise $exerciseId to course $courseId" }

        fetchEms("/teacher/courses/$courseId/exercises", ReqMethod.POST,
            mapOf(
                "exercise_id" to exerciseId,
                "threshold" to 100,
                "student_visible" to false,
                "assessments_student_visible" to true,
            ),
            successChecker = { http200 }
        ).await()
        debug { "Successfully added exercise $exerciseId to course $courseId" }

        successMessage { "Lisatud" }
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
        val courses = CoursesTeacher.getMyCourses()

        val buttons = courses.subList(0, 3).map {
            RadioButtonsComp.Button("${it.title} - ${it.student_count} õpilast", it.id)
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