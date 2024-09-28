package pages.participants

import components.PageTabsComp
import components.modal.ModalComp
import dao.ParticipantsDAO
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str

class AddStudentsModalComp(
    private val courseId: String,
    private val availableGroups: List<ParticipantsDAO.CourseGroup>,
    parent: Component,
) : Component(parent) {

    private lateinit var modalComp: ModalComp<Boolean>
    private lateinit var tabsComp: PageTabsComp

    private lateinit var linkTab: AddStudentsByLinkTabComp

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp = ModalComp(
            Str.addStudents,
            defaultReturnValue = false,
            fixFooter = true,
            isWide = true,
            onOpened = {
                tabsComp.refreshIndicator()
                linkTab.createAndBuild()
            },
            bodyCompsProvider = {
                tabsComp = createTabsComp(it)
                listOf(tabsComp)
            },
            parent = this
        )
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    private fun createTabsComp(parent: Component) =
        PageTabsComp(
            type = PageTabsComp.Type.SUBPAGE,
            tabs = listOf(
                PageTabsComp.Tab(Str.byLink) {
                    AddStudentsByLinkTabComp(courseId, it).also { linkTab = it }
                },
                PageTabsComp.Tab(Str.byEmail) {
                    AddStudentsByEmailTabComp(courseId, availableGroups, { modalComp.closeAndReturnWith(true) }, it)
                },
            ),
            parent = parent
        )
}