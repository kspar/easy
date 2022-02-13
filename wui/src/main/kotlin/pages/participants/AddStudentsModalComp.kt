package pages.participants

import Str
import components.ParagraphsComp
import components.form.TextFieldComp
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

@ExperimentalStdlibApi
class AddStudentsModalComp(
    val courseId: String,
    val availableGroups: List<ParticipantsRootComp.Group>,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Lisa õpilasi", Str.doAdd(), Str.cancel(), Str.adding(),
        primaryAction = { addStudents(studentsFieldComp.getValue()) },
        primaryPostAction = ::reinitialise,
        onOpen = { studentsFieldComp.focus() },
        defaultReturnValue = false,
        fixFooter = true, isWide = true,
        parent = this
    )

    private val helpTextComp = ParagraphsComp(
        listOf(
            "Õpilaste lisamiseks sisesta kasutajate meiliaadressid eraldi ridadele või eraldatuna tühikutega.",
            "Kui sisestatud emaili aadressiga õpilast ei leidu, siis lisatakse õpilane kursusele kasutaja " +
                    "registreerimise hetkel või siis, kui õpilane muudab oma meiliaadressi vastavaks."
        ),
        modalComp
    )

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
        modalComp.setContentComps { listOf(helpTextComp, studentsFieldComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        studentsFieldComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
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

    // TODO: allow adding to group
    private suspend fun addStudents(studentsString: String): Boolean {
        val students = studentsString.split(" ", "\n")
            .filter { it.isNotBlank() }

        debug { "Adding students $students" }
        val newStudents = students.map {
            mapOf("email" to it, "groups" to emptyList<Nothing>())
        }

        val resp = fetchEms("/courses/$courseId/students", ReqMethod.POST, mapOf(
            "students" to newStudents
        ), successChecker = { http200 }).await()
            .parseTo(AddStudentsResp.serializer()).await()

        val active = resp.accesses_added
        val pending = resp.pending_accesses_added_updated
        val msg = "Lisatud $active ${if (active == 1) "aktiivne õpilane" else "aktiivset õpilast"} ja " +
                "lisatud/uuendatud $pending ootel ${if (pending == 1) "kutse" else "kutset"}"

        successMessage { msg }

        return true
    }
}