package pages.participants

import Icons
import components.EzCollComp
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import toEstonianString
import kotlin.js.Date


@ExperimentalStdlibApi
class ParticipantsTeachersListComp(
    private val teachers: List<ParticipantsRootComp.Teacher>,
    private val groups: List<ParticipantsRootComp.Group>,
    private val isEditable: Boolean,
    parent: Component?
) : Component(parent) {

    data class TeacherProps(
        val firstName: String, val lastName: String,
        val email: String, val username: String, val createdAt: Date?,
        val groups: List<GroupProp>
    )

    data class GroupProp(val id: String, val name: String)

    private lateinit var teachersColl: EzCollComp<TeacherProps>

    override val children: List<Component>
        get() = listOf(teachersColl)

    override fun create() = doInPromise {
        val props = teachers.map {
            TeacherProps(
                it.given_name,
                it.family_name,
                it.email,
                it.id,
                it.created_at,
                it.groups.map { GroupProp(it.id, it.name) }
            )
        }

        val hasGroups = groups.isNotEmpty()

        val items = props.map { p ->

            val groupsAttr = if (hasGroups)
                EzCollComp.ListAttr<TeacherProps, String>(
                    "Piiratud rühmad",
                    p.groups.map { EzCollComp.ListAttrItem(it.name) }.toMutableList(),
                    Icons.groups,
//                    onClick = if (isEditable) ::changeGroups else null
                )
            else null

            val actions = if (isEditable)
                listOf(
                    // TODO: add to group and remove from group, same modal as mass action
//                    EzCollComp.Action(Icons.groups, "Rühmad...", onActivate = ::changeGroups),
                    EzCollComp.Action(Icons.removeParticipant, "Eemalda kursuselt", onActivate = ::removeFromCourse),
                )
            else emptyList()

            EzCollComp.Item(
                p,
                EzCollComp.ItemTypeIcon(Icons.teacher),
                "${p.firstName} ${p.lastName}",
                topAttr = groupsAttr,
                bottomAttrs = listOfNotNull(
                    EzCollComp.SimpleAttr("Email", p.email, Icons.email),
                    EzCollComp.SimpleAttr("Kasutajanimi", p.username, Icons.user),
                    p.createdAt?.let {
                        // TODO: date attr
                        EzCollComp.SimpleAttr("Kursusele lisatud", p.createdAt.toEstonianString(), Icons.joinedTime)
                    }
                ),
                isSelectable = isEditable,
                actions = actions
            )
        }

        val massActions = if (isEditable) buildList {
            if (hasGroups) {
                add(
                    EzCollComp.MassAction<TeacherProps>(Icons.addToGroup, "Lisa rühma", { TODO() })
                )
                add(
                    EzCollComp.MassAction<TeacherProps>(Icons.removeFromGroup, "Eemalda rühmast", { TODO() })
                )
            }
            add(
                EzCollComp.MassAction(Icons.removeParticipant, "Eemalda kursuselt", ::removeFromCourse)
            )
        } else emptyList()

        val filterGroups = buildList {
            if (hasGroups) add(
                EzCollComp.FilterGroup(
                    "Rühm",
                    listOf(EzCollComp.Filter<TeacherProps>("Ilma rühmata") { it.props.groups.isEmpty() }) +
                            groups.sortedBy { it.name }.map { g ->
                                EzCollComp.Filter(g.name) { it.props.groups.any { it.id == g.id } }
                            }
                )
            )
        }

        val sorters = buildList {
            if (hasGroups) add(
                EzCollComp.Sorter("Rühma ja nime järgi",
                    compareBy<EzCollComp.Item<TeacherProps>> { it.props.groups.getOrNull(0)?.name }
                        .thenBy { it.props.groups.getOrNull(1)?.name }
                        .thenBy { it.props.groups.getOrNull(2)?.name }
                        .thenBy { it.props.groups.getOrNull(3)?.name }
                        .thenBy { it.props.groups.getOrNull(4)?.name }
                        .thenBy { it.props.lastName.lowercase() }
                        .thenBy { it.props.firstName.lowercase() })
            )
            add(
                EzCollComp.Sorter("Nime järgi",
                    compareBy<EzCollComp.Item<TeacherProps>> { it.props.lastName.lowercase() }
                        .thenBy { it.props.firstName.lowercase() }
                )
            )
        }

        teachersColl = EzCollComp(
            items,
            EzCollComp.Strings("õpetaja", "õpetajat"),
            massActions = massActions,
            filterGroups = filterGroups,
            sorters = sorters,
            parent = this,
        )

    }

    override fun render() = plainDstStr(teachersColl.dstId)

    private suspend fun changeGroups(item: EzCollComp.Item<TeacherProps>): EzCollComp.Result {
        TODO()
    }

    private suspend fun removeFromCourse(item: EzCollComp.Item<TeacherProps>) =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<TeacherProps>>): EzCollComp.Result {
        TODO()
    }
}