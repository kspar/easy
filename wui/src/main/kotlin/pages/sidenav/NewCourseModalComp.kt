package pages.sidenav

import Str
import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class NewCourseModalComp(
    parent: Component,
) : BinaryModalComp<String?>(
    "Uus kursus", Str.doSave(), Str.cancel(), Str.saving(),
    defaultReturnValue = null, dstId = "new-course-modal-dst-id", parent = parent
) {

    @Serializable
    private data class NewCourseDTO(val id: String)

    private val courseTitleField = StringFieldComp(
        "Kursuse nimi",
        true,
        constraints = listOf(StringConstraints.Length(max = 100)),
        onValidChange = ::updateSubmitBtn,
        parent = this
    )

    override fun create() = doInPromise {
        super.create().await()
        super.setContent(courseTitleField)
        super.setPrimaryAction { createCourse(courseTitleField.getValue()) }
        super.setPrimaryPostAction(::reinitialise)
        super.setSecondaryPostAction(::reinitialise)
    }

    override fun postChildrenBuilt() {
        super.postChildrenBuilt()
        courseTitleField.validateAndPaint(false)
    }

    private suspend fun reinitialise() {
        courseTitleField.createAndBuild().await()
        courseTitleField.validateAndPaint(false)
    }

    private fun updateSubmitBtn(isTitleValid: Boolean) {
        super.primaryButtonComp.setEnabled(isTitleValid)
    }

    private suspend fun createCourse(title: String): String {
        debug { "Creating new course with title $title" }
        val courseId = fetchEms("/admin/courses", ReqMethod.POST, mapOf("title" to title),
            successChecker = { http200 }).await()
            .parseTo(NewCourseDTO.serializer()).await().id
        debug { "Saved new course with id $courseId" }
        return courseId
    }
}