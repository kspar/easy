package pages.sidenav

import Str
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
import successMessage

class CreateCourseModalComp(
    parent: Component,
    dstId: String,
) : Component(parent, dstId) {

    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        "Uus kursus", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { createCourse(courseTitleFieldComp.getValue()) },
        primaryPostAction = ::reinitialise, onOpen = { courseTitleFieldComp.focus() },
        defaultReturnValue = null, parent = this
    )

    private val courseTitleFieldComp = StringFieldComp(
        "Kursuse nimi",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 100)),
        onValidChange = ::updateSubmitBtn,
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf(courseTitleFieldComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        courseTitleFieldComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        courseTitleFieldComp.rebuild()
        courseTitleFieldComp.validateInitial()
    }

    private fun updateSubmitBtn(isTitleValid: Boolean) {
        modalComp.primaryButton.setEnabled(isTitleValid)
    }


    @Serializable
    private data class NewCourseDTO(val id: String)

    private suspend fun createCourse(title: String): String {
        debug { "Creating new course with title $title" }
        val courseId = fetchEms("/admin/courses", ReqMethod.POST, mapOf("title" to title),
            successChecker = { http200 }).await()
            .parseTo(NewCourseDTO.serializer()).await().id
        debug { "Saved new course with id $courseId" }
        successMessage { "Kursus loodud" }
        return courseId
    }
}