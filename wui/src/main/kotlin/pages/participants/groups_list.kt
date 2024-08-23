package pages.participants

import HumanStringComparator
import Icons
import components.form.ButtonComp
import components.ezcoll.EzCollComp
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import dao.ParticipantsDAO
import errorMessage
import kotlinx.coroutines.await
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import successMessage
import translation.Str

class ParticipantsGroupsListComp(
    private val courseId: String,
    private val groups: List<ParticipantsDAO.CourseGroup>,
    private val students: List<ParticipantsDAO.Student>,
    private val studentsPending: List<ParticipantsDAO.PendingStudent>,
    private val studentsMoodlePending: List<ParticipantsDAO.PendingMoodleStudent>,
    private val isEditable: Boolean,
    private val onGroupDeleted: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    data class GroupProp(val id: String, val name: String, val studentsCount: Int)

    private lateinit var groupsColl: EzCollComp<GroupProp>
    private lateinit var deleteGroupModal: ConfirmationTextModalComp

    override val children: List<Component>
        get() = listOf(groupsColl, deleteGroupModal)

    override fun create() = doInPromise {
        val props = groups.map { g ->
            val studentsCount = students.count { it.groups.contains(g) } +
                    studentsPending.count { it.groups.contains(g) } +
                    studentsMoodlePending.count { it.groups.contains(g) }

            GroupProp(g.id, g.name, studentsCount)
        }

        val items = props.map { p ->
            EzCollComp.Item(
                p, EzCollComp.ItemTypeIcon(Icons.groups), p.name,
                topAttr = EzCollComp.SimpleAttr(
                    "Rühmas on",
                    "${p.studentsCount} ${if (p.studentsCount == 1) Str.studentsSingular else Str.studentsPlural}",
                    Icons.userUnf
                ),
                actions = buildList {
                    if (isEditable) add(
                        EzCollComp.Action(Icons.delete, "Kustuta",
                            onActivate = ::deleteGroup,
                            onResultModified = { onGroupDeleted() })
                    )
                }
            )
        }

        groupsColl = EzCollComp(
            items, EzCollComp.Strings("rühm", "rühma"),
            sorters = listOf(
                EzCollComp.Sorter("Nimi", compareBy(HumanStringComparator) { it.props.name })
            ),
            parent = this
        )

        deleteGroupModal = ConfirmationTextModalComp(
            null, Str.doDelete, Str.cancel, Str.deleting,
            primaryBtnType = ButtonComp.Type.FILLED_DANGER, parent = this
        )
    }

    override fun render() = plainDstStr(groupsColl.dstId, deleteGroupModal.dstId)

    private suspend fun deleteGroup(group: EzCollComp.Item<GroupProp>): EzCollComp.Result {
        val notEmptyMsg = "See rühm pole tühi. Enne kustutamist eemalda rühmast kõik õpilased."

        if (group.props.studentsCount > 0) {
            errorMessage { notEmptyMsg }
            return EzCollComp.ResultUnmodified
        }

        deleteGroupModal.setText(StringComp.boldTriple("Kustuta ", group.props.name, "?"))
        deleteGroupModal.primaryAction = {
            fetchEms("/courses/$courseId/groups/${group.props.id}", ReqMethod.DELETE, successChecker = { http200 },
                errorHandler = {
                    it.handleByCode(RespError.GROUP_NOT_EMPTY) {
                        errorMessage { notEmptyMsg }
                    }
                }).await()

            true
        }

        return if (deleteGroupModal.openWithClosePromise().await()) {
            successMessage { "Rühm ${group.props.name} kustutatud" }
            EzCollComp.ResultModified<GroupProp>(emptyList())
        } else {
            EzCollComp.ResultUnmodified
        }
    }
}