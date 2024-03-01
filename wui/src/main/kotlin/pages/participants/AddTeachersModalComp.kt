package pages.participants

import components.form.SelectComp
import components.form.TextFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.text.ParagraphsComp
import dao.ParticipantsDAO
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
    private val availableGroups: List<ParticipantsDAO.CourseGroup>,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Lisa õpetajaid", Str.doAdd, Str.cancel, Str.adding,
        defaultReturnValue = false,
        fixFooter = true,
        isWide = true,
        primaryAction = { addTeachers(groupSelectComp?.getValue(), teachersFieldComp.getValue()) },
        primaryPostAction = ::reinitialise, onOpened = { teachersFieldComp.focus() },
        htmlClasses = "add-participants-modal",
        parent = this
    )

    private val helpTextComp = ParagraphsComp(
        listOf("Õpetajate lisamiseks sisesta kasutajate meiliaadressid eraldi ridadele või eraldatuna tühikutega."),
        modalComp
    )

    private val groupSelectComp = if (availableGroups.isNotEmpty())
        SelectComp(
            "Lisa rühma", availableGroups.map { SelectComp.Option(it.name, it.id) }, true,
            parent = modalComp
        ) else null

    private val teachersFieldComp = TextFieldComp(
        "Õpetajate meiliaadressid",
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
        modalComp.setContentComps { listOfNotNull(helpTextComp, groupSelectComp, teachersFieldComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        teachersFieldComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        groupSelectComp?.rebuild()
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

    private suspend fun addTeachers(groupId: String?, teachersString: String): Boolean {
        val teachers = teachersString.split(" ", "\n")
            .filter { it.isNotBlank() }

        debug { "Adding teachers (group $groupId): $teachers" }

        val groups: List<Map<String, String>> = if (groupId != null)
            listOf(mapOf("id" to groupId))
        else emptyList()

        val newTeachers = teachers.map {
            mapOf(
                "email" to it,
                "groups" to groups
            )
        }

        val resp = fetchEms("/courses/$courseId/teachers", ReqMethod.POST, mapOf(
            "teachers" to newTeachers
        ), successChecker = { http200 }, errorHandler = {
            it.handleByCode(RespError.ACCOUNT_EMAIL_NOT_FOUND) {
                val notFoundEmail = it.attrs["email"]!!
                errorMessage { "Ei leidnud õpetajat emailiga '$notFoundEmail'" }
            }
        }).await().parseTo(AddTeachersResp.serializer()).await()

        val added = resp.accesses_added
        val msg = "Lisatud $added ${if (added == 1) "õpetaja" else "õpetajat"}"

        successMessage { msg }

        return true
    }
}