package pages.participants

import Icons
import components.EzCollComp
import errorMessage
import kotlinx.coroutines.await
import plainDstStr
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

@ExperimentalStdlibApi
class ParticipantsGroupsListComp(
    private val courseId: String,
    private val groups: List<ParticipantsRootComp.Group>,
    private val students: List<ParticipantsRootComp.Student>,
    private val studentsPending: List<ParticipantsRootComp.PendingStudent>,
    private val studentsMoodlePending: List<ParticipantsRootComp.PendingMoodleStudent>,
    private val teachers: List<ParticipantsRootComp.Teacher>,
    private val isEditable: Boolean,
    parent: Component?
) : Component(parent) {

    data class GroupProp(val id: String, val name: String, val studentsCount: Int, val teachersCount: Int)

    private lateinit var groupsColl: EzCollComp<GroupProp>

    override val children: List<Component>
        get() = listOf(groupsColl)

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
                    EzCollComp.SimpleAttr("Õpilasi", p.studentsCount, Icons.user),
                    EzCollComp.SimpleAttr("Õpetajaid", p.teachersCount, Icons.teacher),
                    EzCollComp.SimpleAttr("ID", p.id, Icons.id),
                ),
                actions = buildList {
                    if (isEditable) add(
                        EzCollComp.Action(Icons.delete, "Kustuta", onActivate = ::deleteGroup)
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
    }

    override fun render() = plainDstStr(groupsColl.dstId)

    private suspend fun deleteGroup(group: EzCollComp.Item<GroupProp>): EzCollComp.Result {
        // TODO: confirmation modal

        fetchEms("/courses/$courseId/groups/${group.props.id}", ReqMethod.DELETE, successChecker = { http200 },
            errorHandler = {
                it.handleByCode(RespError.GROUP_NOT_EMPTY) {
                    errorMessage { "See rühm pole tühi. Enne kustutamist eemalda rühmast kõik õpilased ja õpetajad." }
                }
            }).await()

        // If await didn't throw then it was a success
        return EzCollComp.ResultModified<GroupProp>(emptyList())
    }
}