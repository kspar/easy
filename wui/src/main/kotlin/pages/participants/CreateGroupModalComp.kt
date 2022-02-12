package pages.participants

import Str
import components.modal.BinaryModalComp
import kotlinx.coroutines.await
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class CreateGroupModalComp(
    val courseId: String,
    parent: Component,
) : Component(parent) {

    private val modalComp: BinaryModalComp<String?> = BinaryModalComp(
        "Loo uus rühm", Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { createGroup() },
        defaultReturnValue = null, parent = this
    )



    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf() }
    }

    override fun render() = plainDstStr(modalComp.dstId)


    fun openWithClosePromise() = modalComp.openWithClosePromise()


    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButtonComp.setEnabled(isFieldValid)
    }


    private suspend fun createGroup(): String? {
        return null
    }
}