package pages.participants

import components.modal.ModalComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class ShowJoinLinkModalComp(
    parent: Component,
) : Component(parent) {

    private lateinit var modalComp: ModalComp<Unit>

    private var modalTitle: String = ""
    private var linkId: String = ""


    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp = ModalComp(
            title = modalTitle,
            defaultReturnValue = Unit,
            isWide = true,
            bodyCompsProvider = {
                listOf(ShowJoinLinkComp(linkId, it))
            },
            parent = this
        )
    }


    fun openWithClosePromise() = modalComp.openWithClosePromise()

    suspend fun setStudent(title: String, studentLinkId: String) {
        modalTitle = title
        linkId = studentLinkId
        createAndBuild().await()
    }
}