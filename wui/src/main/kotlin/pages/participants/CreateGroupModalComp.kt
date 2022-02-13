package pages.participants

import Str
import components.form.StringFieldComp
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
import successMessage

class CreateGroupModalComp(
    val courseId: String,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        "Loo uus rühm", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { createGroup(groupNameFieldComp.getValue()) },
        primaryPostAction = ::reinitialise, onOpen = { groupNameFieldComp.focus() },
        defaultReturnValue = false, parent = this
    )

    private val groupNameFieldComp = StringFieldComp(
        "Rühma nimi",
        true, paintRequiredOnInput = false,
        constraints = listOf(StringConstraints.Length(max = 50)),
        onValidChange = ::updateSubmitBtn,
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

    private suspend fun reinitialise() {
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