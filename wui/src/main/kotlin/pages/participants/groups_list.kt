package pages.participants

import Icons
import components.EzCollComp
import components.text.StringComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.modal.Modal
import errorMessage
import kotlinx.coroutines.await
import rip.kspar.ezspa.plainDstStr
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class ParticipantsGroupsListComp(
    private val courseId: String,
    private val groups: List<ParticipantsRootComp.Group>,
    private val students: List<ParticipantsRootComp.Student>,
    private val studentsPending: List<ParticipantsRootComp.PendingStudent>,
    private val studentsMoodlePending: List<ParticipantsRootComp.PendingMoodleStudent>,
    private val teachers: List<ParticipantsRootComp.Teacher>,
    private val isEditable: Boolean,
    private val onGroupDeleted: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    data class GroupProp(val id: String, val name: String, val studentsCount: Int, val teachersCount: Int)

    private lateinit var groupsColl: EzCollComp<GroupProp>
    private lateinit var deleteGroupModal: ConfirmationTextModalComp

    override val children: List<Component>
        get() = listOf(groupsColl, deleteGroupModal)

    override fun create() = doInPromise {
        val props = groups.map { g ->
            val studentsCount = students.count { it.groups.contains(g) } +
                    studentsPending.count { it.groups.contains(g) } +
                    studentsMoodlePending.count { it.groups.contains(g) }
            val teachersCount = teachers.count { it.groups.contains(g) }

            GroupProp(g.id, g.name, studentsCount, teachersCount)
        }

        val items = props.map { p ->
            EzCollComp.Item(
                p, EzCollComp.ItemTypeIcon(Icons.groups), p.name,
                bottomAttrs = listOf(
                    EzCollComp.SimpleAttr("Õpilasi", p.studentsCount, Icons.userUnf),
                    EzCollComp.SimpleAttr("Õpetajaid", p.teachersCount, Icons.teacherUnf),
                ),
                actions = buildList {
                    if (isEditable) add(
                        EzCollComp.Action(
                            Icons.delete,
                            "Kustuta",
                            onActivate = ::deleteGroup,
                            onResultModified = { onGroupDeleted() })
                    )
                }
            )
        }

        groupsColl = EzCollComp(
            items, EzCollComp.Strings("rühm", "rühma"),
            sorters = listOf(
                EzCollComp.Sorter("Nime järgi", compareBy { it.props.name })
            ),
            parent = this
        )

        deleteGroupModal = ConfirmationTextModalComp(
            null, "Kustuta", "Tühista", "Kustutan...",
            primaryBtnType = ButtonComp.Type.DANGER,
            id = Modal.DELETE_COURSE_GROUP, parent = this
        )
    }

    override fun render() = plainDstStr(groupsColl.dstId, deleteGroupModal.dstId)

    private suspend fun deleteGroup(group: EzCollComp.Item<GroupProp>): EzCollComp.Result {
        val notEmptyMsg = "See rühm pole tühi. Enne kustutamist eemalda rühmast kõik õpilased ja õpetajad."

        if (group.props.studentsCount > 0 || group.props.teachersCount > 0) {
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