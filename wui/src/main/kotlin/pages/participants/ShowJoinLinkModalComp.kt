package pages.participants

import components.modal.ModalComp
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class ShowJoinLinkModalComp(
    parent: Component,
) : Component(parent) {

    private lateinit var modalComp: ModalComp<Unit>

    private var studentEmail: String = ""
    private var studentMoodleId: String = ""
    private var studentLinkId: String = ""


    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp = ModalComp(
            title = Str.courseJoinLink,
            defaultReturnValue = Unit,
            isWide = true,
            bodyCompsProvider = {
                listOf(ShowJoinLinkComp(studentEmail, studentMoodleId, studentLinkId, it))
            },
            parent = this
        )
    }


    fun openWithClosePromise() = modalComp.openWithClosePromise()

    suspend fun setStudent(email: String, moodleId: String, linkId: String) {
        studentEmail = email
        studentMoodleId = moodleId
        studentLinkId = linkId
        createAndBuild().await()
    }
}