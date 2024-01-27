package pages.sidenav

import components.form.StringFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.text.AttrsComp
import dao.CoursesTeacherDAO
import emptyToNull
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class UpdateCourseModalComp(
    private val courseId: String,
    private val courseTitle: String,
    private val courseAlias: String?,
    private val isAdmin: Boolean,
    parent: Component,
) : Component(parent) {

    private lateinit var title: Component
    private lateinit var alias: StringFieldComp

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Kursuse sätted", Str.doSave, Str.cancel, Str.saving,
        defaultReturnValue = false,
        primaryButtonEnabledInitial = false,
        primaryAction = { updateCourse() }, onOpened = { alias.focus() },
        parent = this
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {

        title = if (isAdmin)
            StringFieldComp(
                "Kursuse identifikaator", true, initialValue = courseTitle,
                constraints = listOf(StringConstraints.Length(max = 100)),
                onValidChange = { modalComp.primaryButton.setEnabled(it) },
                onENTER = { modalComp.primaryButton.click() },
                parent = modalComp,
            )
        else
            AttrsComp(
                mapOf("Kursuse identifikaator" to courseTitle),
                parent = modalComp
            )

        alias = StringFieldComp(
            "Kursuse nimi", false,
            initialValue = courseAlias.orEmpty(),
            constraints = listOf(StringConstraints.Length(max = 100)),
            onValidChange = { modalComp.primaryButton.setEnabled(it) },
            onENTER = { modalComp.primaryButton.click() },
            parent = modalComp,
        )

        modalComp.setContentComps { listOf(title, alias) }
    }

    override fun render() = ""

    override fun postChildrenBuilt() {
        (title as? StringFieldComp)?.validateInitial()
        alias.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private suspend fun updateCourse(): Boolean {
        val newTitle = (title as? StringFieldComp)?.getValue() ?: courseTitle
        val newAlias = alias.getValue().emptyToNull()

        CoursesTeacherDAO.updateCourse(courseId, newTitle, newAlias).await()
        return true
    }
}
