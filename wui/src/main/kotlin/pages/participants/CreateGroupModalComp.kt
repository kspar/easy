package pages.participants

import Str
import components.form.StringFieldComp
import components.form.validation.ConstraintViolation
import components.form.validation.FieldConstraint
import components.form.validation.StringConstraints
import components.modal.BinaryModalComp
import components.modal.Modal
import debug
import kotlinx.coroutines.await
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class CreateGroupModalComp(
    val courseId: String,
    val existingGroups: List<ParticipantsRootComp.Group>,
    parent: Component,
) : Component(parent) {

    private val nonDuplicateGroupConstraint: FieldConstraint<String> = object : FieldConstraint<String>() {
        val existingGroupNames = existingGroups.map { it.name }
        override fun validate(value: String, fieldNameForMessage: String): ConstraintViolation<String>? {
            return when {
                existingGroupNames.contains(value) -> violation("Selle nimega rühm on juba kursusel olemas")
                else -> null
            }
        }
    }

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Loo uus rühm", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { createGroup(groupNameFieldComp.getValue()) },
        primaryPostAction = ::reinitialise, onOpen = { groupNameFieldComp.focus() },
        defaultReturnValue = false,
        id = Modal.CREATE_COURSE_GROUP, parent = this
    )

    private val groupNameFieldComp = StringFieldComp(
        "Rühma nimi",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 50), nonDuplicateGroupConstraint),
        onValidChange = ::updateSubmitBtn,
        onENTER = { modalComp.primaryButton.click() },
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf(groupNameFieldComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
        groupNameFieldComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun reinitialise() {
        groupNameFieldComp.rebuild()
        groupNameFieldComp.validateInitial()
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButton.setEnabled(isFieldValid)
    }


    private suspend fun createGroup(groupName: String): Boolean {
        debug { "Creating new group with name $groupName" }

        fetchEms("/courses/$courseId/groups", ReqMethod.POST, mapOf(
            "name" to groupName
        ), successChecker = { http200 }).await()

        successMessage { "Rühm loodud" }

        return true
    }
}