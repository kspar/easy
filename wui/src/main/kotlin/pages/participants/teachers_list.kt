package pages.participants

import Icons
import components.EzCollComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import debug
import kotlinx.coroutines.await
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.plainDstStr
import successMessage
import toEstonianString
import translation.Str
import kotlin.js.Date


class ParticipantsTeachersListComp(
    private val courseId: String,
    private val teachers: List<ParticipantsRootComp.Teacher>,
    private val groups: List<ParticipantsRootComp.Group>,
    private val isEditable: Boolean,
    private val onGroupsChanged: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    data class TeacherProps(
        val firstName: String, val lastName: String,
        val email: String, val username: String, val createdAt: Date?,
        val groups: List<GroupProp>
    )

    data class GroupProp(val id: String, val name: String)

    private lateinit var teachersColl: EzCollComp<TeacherProps>
    private lateinit var removeFromCourseModal: ConfirmationTextModalComp
    private lateinit var addToGroupModal: AddToGroupModalComp
    private lateinit var removeFromGroupModal: RemoveFromGroupModalComp

    override val children: List<Component>
        get() = listOf(teachersColl, removeFromCourseModal, addToGroupModal, removeFromGroupModal)

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
                )
            else null

            val actions = if (isEditable)
                listOf(
                    EzCollComp.Action(Icons.addToGroup, "Lisa rühma", onActivate = ::addToGroup),
                    EzCollComp.Action(Icons.removeFromGroup, "Eemalda rühmast", onActivate = ::removeFromGroup),
                    EzCollComp.Action(Icons.removeParticipant, "Eemalda kursuselt", onActivate = ::removeFromCourse),
                )
            else emptyList()

            EzCollComp.Item(
                p,
                EzCollComp.ItemTypeIcon(Icons.teacher),
                "${p.firstName} ${p.lastName}",
                topAttr = groupsAttr,
                bottomAttrs = listOfNotNull(
                    EzCollComp.SimpleAttr("Email", p.email, Icons.emailUnf),
                    EzCollComp.SimpleAttr("Kasutajanimi", p.username, Icons.userUnf),
                    p.createdAt?.let {
                        // TODO: date attr
                        EzCollComp.SimpleAttr("Kursusele lisatud", p.createdAt.toEstonianString(), Icons.joinedTimeUnf)
                    }
                ),
                isSelectable = isEditable,
                actions = actions
            )
        }

        val massActions = if (isEditable) buildList {
            if (hasGroups) {
                add(
                    EzCollComp.MassAction<TeacherProps>(Icons.addToGroup, "Lisa rühma", ::addToGroup)
                )
                add(
                    EzCollComp.MassAction<TeacherProps>(Icons.removeFromGroup, "Eemalda rühmast", ::removeFromGroup)
                )
            }
            add(
                EzCollComp.MassAction(Icons.removeParticipant, "Eemalda kursuselt", ::removeFromCourse)
            )
        } else emptyList()

        val filterGroups = buildList {
            if (hasGroups) add(
                EzCollComp.FilterGroup(
                    Str.accountGroup,
                    listOf(EzCollComp.Filter<TeacherProps>("Ilma rühmata") { it.props.groups.isEmpty() }) +
                            groups.map { g ->
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

        removeFromCourseModal = ConfirmationTextModalComp(
            null, "Eemalda", "Tühista", "Eemaldan...",
            primaryBtnType = ButtonComp.Type.DANGER, parent = this
        )

        addToGroupModal = AddToGroupModalComp(courseId, groups, AddToGroupModalComp.For.TEACHER, parent = this)
        removeFromGroupModal = RemoveFromGroupModalComp(
            courseId, groups, RemoveFromGroupModalComp.For.TEACHER, parent = this
        )
    }

    override fun render() = plainDstStr(
        teachersColl.dstId, removeFromCourseModal.dstId, addToGroupModal.dstId, removeFromGroupModal.dstId
    )


    private suspend fun addToGroup(item: EzCollComp.Item<TeacherProps>) =
        addToGroup(listOf(item))

    private suspend fun addToGroup(items: List<EzCollComp.Item<TeacherProps>>): EzCollComp.Result {
        val text = if (items.size == 1)
            StringComp.boldTriple("Lisa õpetaja ", items[0].title, " rühma:")
        else
            StringComp.boldTriple("Lisa ", items.size.toString(), " õpetajat rühma:")

        addToGroupModal.setText(text)
        addToGroupModal.participants = items.map { AddToGroupModalComp.Participant(teacherId = it.props.username) }

        val newGroup = addToGroupModal.openWithClosePromise().await()

        if (newGroup != null) {
            onGroupsChanged()
        }
        return EzCollComp.ResultUnmodified
    }


    private suspend fun removeFromGroup(item: EzCollComp.Item<TeacherProps>) =
        removeFromGroup(listOf(item))

    private suspend fun removeFromGroup(items: List<EzCollComp.Item<TeacherProps>>): EzCollComp.Result {
        val text = if (items.size == 1)
            StringComp.boldTriple("Eemalda õpetaja ", items[0].title, " rühmast:")
        else
            StringComp.boldTriple("Eemalda ", items.size.toString(), " õpetajat rühmast:")

        removeFromGroupModal.setText(text)
        val canRemove = removeFromGroupModal.setParticipants(
            items.map {
                RemoveFromGroupModalComp.Participant(
                    teacherId = it.props.username,
                    groups = it.props.groups.map { ParticipantsRootComp.Group(it.id, it.name) }
                )
            }
        )

        if (!canRemove) {
            return EzCollComp.ResultUnmodified
        }

        val removed = removeFromGroupModal.openWithClosePromise().await()
        if (removed) {
            onGroupsChanged()
        }
        return EzCollComp.ResultUnmodified
    }


    private suspend fun removeFromCourse(item: EzCollComp.Item<TeacherProps>) =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<TeacherProps>>): EzCollComp.Result {
        debug { "Removing teachers ${items.map { it.title }}?" }

        val text = if (items.size == 1)
            StringComp.boldTriple("Eemalda õpetaja ", items[0].title, "?")
        else
            StringComp.boldTriple("Eemalda ", items.size.toString(), " õpetajat?")

        removeFromCourseModal.setText(text)
        removeFromCourseModal.primaryAction = {
            debug { "Remove confirmed" }

            val body = mapOf("teachers" to
                    items.map {
                        mapOf("id" to it.props.username)
                    }
            )

            fetchEms(
                "/courses/$courseId/teachers", ReqMethod.DELETE,
                body, successChecker = { http200 }
            ).await()

            successMessage { "Eemaldatud" }

            true
        }

        val removed = removeFromCourseModal.openWithClosePromise().await()

        if (removed) {
            onGroupsChanged()
        }
        return EzCollComp.ResultUnmodified
    }
}