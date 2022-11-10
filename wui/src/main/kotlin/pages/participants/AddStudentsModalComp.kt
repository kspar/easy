package pages.participants

import Str
import components.ParagraphsComp
import components.form.SelectComp
import components.form.TextFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.modal.Modal
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

class AddStudentsModalComp(
    private val courseId: String,
    private val availableGroups: List<ParticipantsRootComp.Group>,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Lisa õpilasi", Str.doAdd(), Str.cancel(), Str.adding(),
        primaryAction = { addStudents(groupSelectComp?.getValue(), studentsFieldComp.getValue()) },
        primaryPostAction = ::reinitialise,
        onOpen = { studentsFieldComp.focus() },
        defaultReturnValue = false,
        fixFooter = true, isWide = true,
        id = Modal.ADD_STUDENTS_TO_COURSE, parent = this
    )

    private val helpTextComp = ParagraphsComp(
        listOf(
            "Õpilaste lisamiseks sisesta kasutajate meiliaadressid eraldi ridadele või eraldatuna tühikutega.",
            "Kui sisestatud emaili aadressiga õpilast ei leidu, siis lisatakse õpilane kursusele kasutaja " +
                    "registreerimise hetkel või siis, kui õpilane muudab oma meiliaadressi vastavaks."
        ),
        modalComp
    )

    private val groupSelectComp = if (availableGroups.isNotEmpty())
        SelectComp(
            "Lisa rühma", availableGroups.map { SelectComp.Option(it.name, it.id) }, true,
            parent = modalComp
        ) else null

    private val studentsFieldComp = TextFieldComp(
        "Õpilaste meiliaadressid",
        true,
        "oskar@ohakas.ee &#x0a;mari@maasikas.com",
        startActive = true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 10000)),
        onValidChange = ::updateSubmitBtn,
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOfNotNull(helpTextComp, groupSelectComp, studentsFieldComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        studentsFieldComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        groupSelectComp?.rebuild()
        studentsFieldComp.rebuild()
        studentsFieldComp.validateInitial()
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButton.setEnabled(isFieldValid)
    }


    @Serializable
    private data class AddStudentsResp(
        val accesses_added: Int,
        val pending_accesses_added_updated: Int,
    )

    private suspend fun addStudents(groupId: String?, studentsString: String): Boolean {
        val students = studentsString.split(" ", "\n")
            .filter { it.isNotBlank() }

        debug { "Adding students (group $groupId): $students" }

        val groups: List<Map<String, String>> = if (groupId != null)
            listOf(mapOf("id" to groupId))
        else emptyList()

        val newStudents = students.map {
            mapOf(
                "email" to it,
                "groups" to groups
            )
        }

        val resp = fetchEms("/courses/$courseId/students", ReqMethod.POST, mapOf(
            "students" to newStudents
        ), successChecker = { http200 }).await()
            .parseTo(AddStudentsResp.serializer()).await()

        val active = resp.accesses_added
        val pending = resp.pending_accesses_added_updated
        val msg = (if (active > 0)
            "Lisatud $active ${if (active == 1) "aktiivne õpilane" else "aktiivset õpilast"}. " else "") +
                if (pending > 0)
                    "Lisatud/uuendatud $pending ootel ${if (pending == 1) "kutse" else "kutset"}."
                else ""

        successMessage { msg }

        return true
    }
}