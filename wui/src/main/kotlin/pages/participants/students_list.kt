package pages.participants

import Icons
import components.EzCollComp
import components.text.StringComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.modal.Modal
import dao.ParticipantsDAO
import debug
import errorMessage
import kotlinx.coroutines.await
import rip.kspar.ezspa.plainDstStr
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class ParticipantsStudentsListComp(
    private val courseId: String,
    private val students: List<ParticipantsRootComp.Student>,
    private val studentsPending: List<ParticipantsRootComp.PendingStudent>,
    private val studentsMoodlePending: List<ParticipantsRootComp.PendingMoodleStudent>,
    private val groups: List<ParticipantsRootComp.Group>,
    private val isEditable: Boolean,
    private val onGroupsChanged: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    data class StudentProps(
        val firstName: String?, val lastName: String?,
        val email: String, val username: String?, val utUsername: String?,
        val isActive: Boolean, val groups: List<GroupProp>
    )

    data class GroupProp(val id: String, val name: String)

    private lateinit var studentsColl: EzCollComp<StudentProps>
    private lateinit var removeFromCourseModal: ConfirmationTextModalComp
    private lateinit var addToGroupModal: AddToGroupModalComp
    private lateinit var removeFromGroupModal: RemoveFromGroupModalComp

    override val children: List<Component>
        get() = listOf(studentsColl, removeFromCourseModal, addToGroupModal, removeFromGroupModal)

    override fun create() = doInPromise {

        val activeStudentProps = students.map {
            StudentProps(
                it.given_name,
                it.family_name,
                it.email,
                it.id,
                it.moodle_username,
                true,
                it.groups.map { GroupProp(it.id, it.name) },
            )
        }
        val pendingStudentProps = studentsPending.map {
            StudentProps(
                null, null, it.email, null, null, false,
                it.groups.map { GroupProp(it.id, it.name) })
        }
        val moodlePendingStudentProps = studentsMoodlePending.map {
            StudentProps(
                null, null, it.email, null, it.moodle_username, false,
                it.groups.map { GroupProp(it.id, it.name) })
        }

        val studentProps = activeStudentProps + pendingStudentProps + moodlePendingStudentProps

        val hasGroups = groups.isNotEmpty()

        val items = studentProps.map { p ->
            EzCollComp.Item(
                p,
                EzCollComp.ItemTypeIcon(if (p.isActive) Icons.user else Icons.pending),
                if (p.isActive) "${p.firstName} ${p.lastName}" else "(Kutse ootel)",
                titleStatus = if (p.isActive) EzCollComp.TitleStatus.NORMAL else EzCollComp.TitleStatus.INACTIVE,
                topAttr = if (hasGroups) EzCollComp.ListAttr(
                    "Rühmad",
                    p.groups.map { EzCollComp.ListAttrItem(it.name) }.toMutableList(),
                    Icons.groupsUnf,
                ) else null,
                bottomAttrs = buildList<EzCollComp.Attr<StudentProps>> {
                    add(EzCollComp.SimpleAttr("Email", p.email, Icons.emailUnf))
                    p.username?.let { add(EzCollComp.SimpleAttr("Kasutajanimi", p.username, Icons.userUnf)) }
                    p.utUsername?.let { add(EzCollComp.SimpleAttr("UT kasutajanimi", p.utUsername, Icons.utUserUnf)) }
                },
                isSelectable = isEditable,
                actions = if (isEditable) buildList {
                    if (!p.isActive)
                        add(EzCollComp.Action(Icons.sendEmail, "Saada kutse", onActivate = ::sendInvite))
                    add(EzCollComp.Action(Icons.addToGroup, "Lisa rühma", onActivate = ::addToGroup))
                    add(EzCollComp.Action(Icons.removeFromGroup, "Eemalda rühmast", onActivate = ::removeFromGroup))
                    add(
                        EzCollComp.Action(
                            Icons.removeParticipant,
                            "Eemalda kursuselt",
                            onActivate = ::removeFromCourse
                        )
                    )
                }
                else emptyList(),
            )
        }

        val massActions = if (isEditable) buildList {
            if (hasGroups) {
                add(
                    EzCollComp.MassAction<StudentProps>(Icons.addToGroup, "Lisa rühma", ::addToGroup)
                )
                add(
                    EzCollComp.MassAction<StudentProps>(Icons.removeFromGroup, "Eemalda rühmast", ::removeFromGroup)
                )
            }
            add(
                EzCollComp.MassAction(Icons.removeParticipant, "Eemalda kursuselt", ::removeFromCourse)
            )
        } else emptyList()

        studentsColl = EzCollComp(
            items,
            EzCollComp.Strings("õpilane", "õpilast"),
            massActions = massActions,
            filterGroups = buildList {
                add(
                    EzCollComp.FilterGroup<StudentProps>(
                        "Staatus", listOf(
                            EzCollComp.Filter("Aktiivne") { it.props.isActive },
                            EzCollComp.Filter("Ootel") { !it.props.isActive },
                        )
                    )
                )
                if (hasGroups)
                    add(
                        EzCollComp.FilterGroup(
                            "Rühm",
                            listOf(EzCollComp.Filter<StudentProps>("Ilma rühmata") { it.props.groups.isEmpty() }) +
                                    groups.map { g ->
                                        EzCollComp.Filter(g.name) { it.props.groups.any { it.id == g.id } }
                                    }
                        )
                    )
            },
            sorters = buildList {
                if (hasGroups)
                    add(
                        EzCollComp.Sorter("Rühma ja nime järgi",
                            compareBy<EzCollComp.Item<StudentProps>> { it.props.groups.getOrNull(0)?.name }
                                .thenBy { it.props.groups.getOrNull(1)?.name }
                                .thenBy { it.props.groups.getOrNull(2)?.name }
                                .thenBy { it.props.groups.getOrNull(3)?.name }
                                .thenBy { it.props.groups.getOrNull(4)?.name }
                                .thenBy { it.props.lastName?.lowercase() ?: it.props.email.lowercase() }
                                .thenBy { it.props.firstName?.lowercase() })
                    )
                add(
                    EzCollComp.Sorter("Nime järgi",
                        compareBy<EzCollComp.Item<StudentProps>> {
                            it.props.lastName?.lowercase() ?: it.props.email.lowercase()
                        }
                            .thenBy { it.props.firstName?.lowercase() }
                    )
                )
            },
            parent = this
        )

        removeFromCourseModal = ConfirmationTextModalComp(
            null, "Eemalda", "Tühista", "Eemaldan...",
            primaryBtnType = ButtonComp.Type.DANGER,
            id = Modal.REMOVE_STUDENTS_FROM_COURSE, parent = this
        )

        addToGroupModal = AddToGroupModalComp(courseId, groups, AddToGroupModalComp.For.STUDENT, parent = this)
        removeFromGroupModal = RemoveFromGroupModalComp(
            courseId, groups, RemoveFromGroupModalComp.For.STUDENT, parent = this
        )
    }

    override fun render() = plainDstStr(
        studentsColl.dstId, removeFromCourseModal.dstId, addToGroupModal.dstId, removeFromGroupModal.dstId
    )

    private suspend fun addToGroup(item: EzCollComp.Item<StudentProps>) =
        addToGroup(listOf(item))

    private suspend fun addToGroup(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        val text = if (items.size == 1) {
            val item = items[0]
            val id = if (item.props.isActive) item.title else item.props.email
            StringComp.boldTriple("Lisa õpilane ", id, " rühma:")
        } else {
            StringComp.boldTriple("Lisa ", items.size.toString(), " õpilast rühma:")
        }

        addToGroupModal.setText(text)

        val (active, pending) = items.partition { it.props.isActive }
        addToGroupModal.participants =
            active.map { AddToGroupModalComp.Participant(studentId = it.props.username) } +
                    pending.map { AddToGroupModalComp.Participant(pendingStudentEmail = it.props.email) }

        val newGroup = addToGroupModal.openWithClosePromise().await()

        if (newGroup != null) {
            onGroupsChanged()
        }
        return EzCollComp.ResultUnmodified
    }


    private suspend fun removeFromGroup(item: EzCollComp.Item<StudentProps>) =
        removeFromGroup(listOf(item))

    private suspend fun removeFromGroup(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        val text = if (items.size == 1) {
            val item = items[0]
            val id = if (item.props.isActive) item.title else item.props.email
            StringComp.boldTriple("Eemalda õpilane ", id, " rühmast:")
        } else {
            StringComp.boldTriple("Eemalda ", items.size.toString(), " õpilast rühmast:")
        }

        removeFromGroupModal.setText(text)
        val (active, pending) = items.partition { it.props.isActive }
        val canRemove = removeFromGroupModal.setParticipants(
            active.map {
                RemoveFromGroupModalComp.Participant(
                    studentId = it.props.username,
                    groups = it.props.groups.map { ParticipantsRootComp.Group(it.id, it.name) }
                )
            } + pending.map {
                RemoveFromGroupModalComp.Participant(
                    pendingStudentEmail = it.props.email,
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


    private suspend fun removeFromCourse(item: EzCollComp.Item<StudentProps>): EzCollComp.Result =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        debug { "Removing students ${items.map { it.title }}?" }

        val text = if (items.size == 1) {
            val item = items[0]
            val id = if (item.props.isActive) item.title else item.props.email
            StringComp.boldTriple("Eemalda õpilane ", id, "?")
        } else {
            StringComp.boldTriple("Eemalda ", items.size.toString(), " õpilast?")
        }

        removeFromCourseModal.setText(text)
        removeFromCourseModal.primaryAction = {
            debug { "Remove confirmed" }

            val (active, pending) = items.partition { it.props.isActive }

            val body = mapOf(
                "active_students" to active.map {
                    mapOf("id" to it.props.username)
                },
                "pending_students" to pending.map {
                    mapOf("email" to it.props.email)
                }
            )

            fetchEms(
                "/courses/$courseId/students", ReqMethod.DELETE, body,
                successChecker = { http200 }, errorHandler = {
                    it.handleByCode(RespError.NO_GROUP_ACCESS) {
                        errorMessage { "Sul pole lubatud õpilast ${it.attrs["studentIdentifier"]} kursuselt eemaldada, sest ta pole sinu rühmas" }
                    }
                }
            ).await()

            successMessage { "Eemaldatud" }

            true
        }

        val removed = removeFromCourseModal.openWithClosePromise().await()

        return if (removed)
            EzCollComp.ResultModified<StudentProps>(emptyList())
        else
            EzCollComp.ResultUnmodified
    }

    private suspend fun sendInvite(item: EzCollComp.Item<StudentProps>): EzCollComp.Result = sendInvite(listOf(item))

    private suspend fun sendInvite(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        // TODO: allow active students ("saada teavitus") as well
        // TODO: mass action

        ParticipantsDAO.sendStudentCourseInvites(courseId, items.map { it.props.email }).await()
        successMessage { "Saadetud" }
        return EzCollComp.ResultUnmodified
    }
}

