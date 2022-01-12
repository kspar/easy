package pages.participants

import Str
import components.ParagraphsComp
import components.StringComp
import components.form.TextFieldComp
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import debug
import kotlinx.coroutines.await
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AddStudentsModalComp(
    val courseId: String,
    parent: Component,
) : BinaryModalComp<Boolean>(
    "Lisa õpilasi", Str.doSave(), Str.cancel(), Str.saving(),
    defaultReturnValue = false, parent = parent
) {

    private val helpText = ParagraphsComp(
        listOf("Õpilaste lisamiseks sisesta kasutajate meiliaadressid eraldi ridadele või eraldatuna tühikutega.",
        "Kui sisestatud emaili aadressiga õpilast ei leidu, siis lisatakse õpilane kursusele kasutaja registreerimise hetkel."),
        this
    )

    private val studentsField = TextFieldComp(
        "Õpilaste meiliaadressid",
        true,
        "oskar@ohakas.ee &#x0a;mari@maasikas.com",
        startActive = true,
        constraints = listOf(StringConstraints.Length(max = 10000)),
        onValidChange = ::updateSubmitBtn,
        parent = this
    )

    override fun create() = doInPromise {
        super.create().await()
        super.setContent(helpText, studentsField)
        super.setPrimaryAction { addStudents(studentsField.getValue()) }
        super.setPrimaryPostAction(::reinitialise)
        super.setSecondaryPostAction(::reinitialise)
    }

    override fun postChildrenBuilt() {
        super.postChildrenBuilt()
        studentsField.validateAndPaint(false)
    }

    private suspend fun reinitialise() {
        studentsField.createAndBuild().await()
        studentsField.validateAndPaint(false)
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        super.primaryButtonComp.setEnabled(isFieldValid)
    }

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