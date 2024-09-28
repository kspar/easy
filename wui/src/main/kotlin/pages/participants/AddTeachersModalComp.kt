package pages.participants

import components.form.TextFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.text.ParagraphsComp
import debug
import errorMessage
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import successMessage
import translation.Str

class AddTeachersModalComp(
    private val courseId: String,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        Str.addTeachers, Str.doAdd, Str.cancel, Str.adding,
        defaultReturnValue = false,
        fixFooter = true,
        isWide = true,
        primaryAction = { addTeachers(teachersFieldComp.getValue()) },
        primaryPostAction = ::reinitialise, onOpened = { teachersFieldComp.focus() },
        htmlClasses = "add-participants-modal",
        parent = this
    )

    private val helpTextComp = ParagraphsComp(
        listOf(Str.teacherAddHelpText),
        modalComp
    )

    private val teachersFieldComp = TextFieldComp(
        Str.teachersEmails,
        true,
        "oskar@opetaja.ee &#x0a;mari@opetaja.com",
        startActive = true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 10000)),
        onValidChange = ::updateSubmitBtn,
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOfNotNull(helpTextComp, teachersFieldComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        teachersFieldComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        teachersFieldComp.rebuild()
        teachersFieldComp.validateInitial()
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButton.setEnabled(isFieldValid)
    }


    @Serializable
    private data class AddTeachersResp(
        val accesses_added: Int,
    )

    private suspend fun addTeachers(teachersString: String): Boolean {
        val teachers = teachersString.split(" ", "\n")
            .filter { it.isNotBlank() }

        debug { "Adding teachers: $teachers" }

        val newTeachers = teachers.map {
            mapOf(
                "email" to it,
            )
        }

        val resp = fetchEms("/courses/$courseId/teachers", ReqMethod.POST, mapOf(
            "teachers" to newTeachers
        ), successChecker = { http200 }, errorHandler = {
            it.handleByCode(RespError.ACCOUNT_EMAIL_NOT_FOUND) {
                val notFoundEmail = it.attrs["email"]!!
                errorMessage { Str.teacherEmailNotFound + notFoundEmail }
            }
        }).await().parseTo(AddTeachersResp.serializer()).await()

        val added = resp.accesses_added
        val msg = "${Str.added} $added ${if (added == 1) Str.teachersSingular else Str.teachersPlural}"

        successMessage { msg }

        return true
    }
}