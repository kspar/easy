package pages.participants

import Icons
import components.form.OldButtonComp
import components.form.SelectComp
import components.form.TextFieldComp
import components.form.validation.StringConstraints
import components.text.ParagraphsComp
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage
import template
import translation.Str

class AddStudentsByEmailTabComp(
    private val courseId: String,
    private val availableGroups: List<ParticipantsRootComp.Group>,
    private val onAddingFinished: () -> Unit,
    parent: Component,
) : Component(parent) {

    private lateinit var helpText: ParagraphsComp
    private var groupSelect: SelectComp? = null
    private lateinit var emailField: TextFieldComp
    private lateinit var submitBtn: OldButtonComp

    override val children: List<Component>
        get() = listOfNotNull(helpText, groupSelect, emailField, submitBtn)

    override fun create() = doInPromise {
        helpText = ParagraphsComp(
            listOf(
                "Sisesta kasutajate meiliaadressid eraldi ridadele või eraldatuna tühikutega.",
                "Kui sisestatud emaili aadressiga õpilast ei leidu, siis lisatakse õpilane kursusele kasutaja " +
                        "registreerimise hetkel või siis, kui õpilane muudab oma meiliaadressi vastavaks."
            ), this
        )

        groupSelect = if (availableGroups.isNotEmpty())
            SelectComp(
                "Lisa rühma", availableGroups.map { SelectComp.Option(it.name, it.id) }, true,
                parent = this
            ) else null

        emailField = TextFieldComp(
            "Õpilaste meiliaadressid",
            true,
            "oskar@ohakas.ee &#x0a;mari@maasikas.com",
            startActive = true, paintRequiredOnInput = false,
            constraints = listOf(StringConstraints.Length(max = 10000)),
            onValidChange = ::updateSubmitBtn,
            parent = this
        )

        submitBtn = OldButtonComp(
            OldButtonComp.Type.PRIMARY,
            label = Str.doAdd, clickedLabel = Str.adding, iconHtml = Icons.add,
            onClick = {
                addStudents(groupSelect?.getValue(), emailField.getValue())
                onAddingFinished()
            },
            postClick = ::reinitialise,
            parent = this,
        )
    }

    override fun render() = template(
        """
        <div class='add-participants-modal' style='margin-top: 2.5rem;'>${super.render()}</div>
        """.trimIndent()
    )

    override fun postChildrenBuilt() {
        emailField.validateInitial()
    }

    private fun reinitialise() {
        groupSelect?.rebuild()
        emailField.rebuild()
        emailField.validateInitial()
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        submitBtn.setEnabled(isFieldValid)
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
