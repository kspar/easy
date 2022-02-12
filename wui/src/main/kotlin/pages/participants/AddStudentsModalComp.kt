package pages.participants

import Str
import components.ParagraphsComp
import components.form.TextFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import debug
import kotlinx.coroutines.await
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AddStudentsModalComp(
    val courseId: String,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Lisa õpilasi", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { addStudents(studentsFieldComp.getValue()) },
        primaryPostAction = ::reinitialise,
        defaultReturnValue = false, isWide = true, parent = this
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
        startActive = true,
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
        super.postChildrenBuilt()
        studentsFieldComp.validateAndPaint(false)
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private suspend fun reinitialise() {
        studentsFieldComp.createAndBuild().await()
        studentsFieldComp.validateAndPaint(false)
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButtonComp.setEnabled(isFieldValid)
    }

    // TODO: allow adding to group
    // TODO: success message
    private suspend fun addStudents(studentsString: String): Boolean {
        val students = studentsString.split(" ", "\n")
            .filter { it.isNotBlank() }

        debug { "Adding students $students" }
        val newStudents = students.map {
            mapOf("email" to it, "groups" to emptyList<Nothing>())
        }

        fetchEms("/courses/$courseId/students", ReqMethod.POST, mapOf(
            "students" to newStudents
        ), successChecker = { http200 }).await()
        return true
    }
}