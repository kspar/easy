package pages.participants

import HumanStringComparator
import Icons
import addNotNull
import components.ToastThing
import components.ezcoll.EzCollComp
import components.ezcoll.EzCollConf
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import dao.ParticipantsDAO
import debug
import errorMessage
import kotlinx.coroutines.await
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.Key
import successMessage
import translation.Str

class ParticipantsStudentsListComp(
    private val courseId: String,
    private val students: List<ParticipantsDAO.Student>,
    private val studentsPending: List<ParticipantsDAO.PendingStudent>,
    private val studentsMoodlePending: List<ParticipantsDAO.PendingMoodleStudent>,
    private val groups: List<ParticipantsDAO.CourseGroup>,
    private val isMoodleSynced: Boolean,
    private val onGroupsChanged: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    data class StudentProps(
        val firstName: String?, val lastName: String?,
        val email: String, val username: String?, val utUsername: String?, val utInviteId: String?,
        val isActive: Boolean, override val groups: List<ParticipantsDAO.CourseGroup>
    ) : EzCollComp.WithGroups

    private lateinit var coll: EzCollComp<StudentProps>
    private lateinit var removeFromCourseModal: ConfirmationTextModalComp
    private lateinit var addToGroupModal: AddToGroupModalComp
    private lateinit var removeFromGroupModal: RemoveFromGroupModalComp
    private lateinit var showJoinLinkModal: ShowJoinLinkModalComp

    override val children: List<Component>
        get() = listOf(coll, removeFromCourseModal, addToGroupModal, removeFromGroupModal, showJoinLinkModal)

    override fun create() = doInPromise {

        val activeStudentProps = students.map {
            StudentProps(it.given_name, it.family_name, it.email, it.id, it.moodle_username, null, true, it.groups)
        }
        val pendingStudentProps = studentsPending.map {
            StudentProps(null, null, it.email, null, null, null, false, it.groups)
        }
        val moodlePendingStudentProps = studentsMoodlePending.map {
            StudentProps(null, null, it.email, null, it.moodle_username, it.invite_id, false, it.groups)
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
                bottomAttrs = buildList {
                    add(EzCollComp.SimpleAttr("Email", p.email, Icons.emailUnf))
                    p.username?.let { add(EzCollComp.SimpleAttr("Kasutajanimi", p.username, Icons.userUnf)) }
                    p.utUsername?.let { add(EzCollComp.SimpleAttr("UT kasutajanimi", p.utUsername, Icons.utUserUnf)) }
                },
                isSelectable = true,
                actions = buildList {
                    // Invites cannot be sent to active Moodle course students
                    if (!(isMoodleSynced && p.isActive))
                        add(EzCollComp.Action(Icons.sendEmail, "Saada kutse", onActivate = ::sendInvite))

                    if (!isMoodleSynced) {
                        add(EzCollComp.Action(Icons.addToGroup, "Lisa rühma", onActivate = ::addToGroup))
                        add(EzCollComp.Action(Icons.removeFromGroup, "Eemalda rühmast", onActivate = ::removeFromGroup))
                    }

                    // Moodle course invites cannot be deleted
                    if (!(isMoodleSynced && !p.isActive))
                        add(
                            EzCollComp.Action(
                                Icons.removeParticipant, "Eemalda kursuselt", onActivate = ::removeFromCourse
                            )
                        )

                    if (isMoodleSynced && !p.isActive && p.utInviteId != null && p.utUsername != null)
                        add(
                            EzCollComp.Action(
                                Icons.share, "Näita liitumislinki",
                                onActivate = {
                                    showJoinLinkModal.setStudent(p.email, p.utUsername, p.utInviteId)
                                    showJoinLinkModal.openWithClosePromise().await()
                                    EzCollComp.ResultUnmodified
                                }
                            )
                        )
                },
            )
        }

        val massActions = buildList {
            if (!isMoodleSynced && hasGroups) {
                add(EzCollComp.MassAction(Icons.addToGroup, "Lisa rühma", ::addToGroup))
                add(EzCollComp.MassAction(Icons.removeFromGroup, "Eemalda rühmast", ::removeFromGroup))
            }
            add(EzCollComp.MassAction(Icons.sendEmail, "Saada kutse", ::sendInvite))
            add(EzCollComp.MassAction(Icons.removeParticipant, "Eemalda kursuselt", ::removeFromCourse))
        }

        coll = EzCollComp(
            items,
            EzCollComp.Strings(Str.studentsSingular, Str.studentsPlural),
            massActions = massActions,
            filterGroups = buildList {
                addNotNull(EzCollComp.createGroupFilter(groups))
                add(
                    EzCollComp.FilterGroup(
                        "Olek", listOf(
                            EzCollComp.Filter("Aktiivne", confType = EzCollConf.ParticipantsFilter.STATE_ACTIVE) {
                                it.props.isActive
                            },
                            EzCollComp.Filter("Ootel", confType = EzCollConf.ParticipantsFilter.STATE_PENDING) {
                                !it.props.isActive
                            },
                        )
                    )
                )
            },
            sorters = buildList {
                if (hasGroups)
                    add(
                        EzCollComp.Sorter(
                            "Rühm / nimi",
                            compareBy<EzCollComp.Item<StudentProps>, String?>(HumanStringComparator) {
                                it.props.groups.getOrNull(0)?.name
                            }
                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(1)?.name }
                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(2)?.name }
                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(3)?.name }
                                .thenBy(HumanStringComparator) { it.props.groups.getOrNull(4)?.name }
                                .thenBy { it.props.lastName?.lowercase() ?: it.props.email.lowercase() }
                                .thenBy { it.props.firstName?.lowercase() },
                            confType = EzCollConf.ParticipantsSorter.GROUP_NAME,
                        )
                    )
                add(
                    EzCollComp.Sorter(
                        "Nimi",
                        compareBy<EzCollComp.Item<StudentProps>> {
                            it.props.lastName?.lowercase() ?: it.props.email.lowercase()
                        }
                            .thenBy { it.props.firstName?.lowercase() },
                        confType = EzCollConf.ParticipantsSorter.NAME,
                    )
                )
            },
            userConf = EzCollConf.UserConf.retrieve(Key.COURSE_PARTICIPANTS_USER_CONF, courseId),
            onConfChange = { it.store(Key.COURSE_PARTICIPANTS_USER_CONF, courseId, hasCourseGroupFilter = true) },
            parent = this
        )

        removeFromCourseModal = ConfirmationTextModalComp(
            null, Str.doRemove, Str.cancel, Str.removing,
            primaryBtnType = ButtonComp.Type.FILLED_DANGER, parent = this
        )

        addToGroupModal = AddToGroupModalComp(courseId, groups, AddToGroupModalComp.For.STUDENT, parent = this)
        removeFromGroupModal = RemoveFromGroupModalComp(
            courseId, groups, RemoveFromGroupModalComp.For.STUDENT, parent = this
        )
        showJoinLinkModal = ShowJoinLinkModalComp(this)
    }


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
                    groups = it.props.groups.map { ParticipantsDAO.CourseGroup(it.id, it.name) }
                )
            } + pending.map {
                RemoveFromGroupModalComp.Participant(
                    pendingStudentEmail = it.props.email,
                    groups = it.props.groups.map { ParticipantsDAO.CourseGroup(it.id, it.name) }
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

        val validItems = items.filter { it.props.isActive || !isMoodleSynced }

        if (validItems.isEmpty()) {
            ToastThing(
                "Kutseid ei saa eemaldada. Tee muudatused Moodle'i kursusel ja seejärel sünkroniseeri õpilased.",
                icon = ToastThing.ERROR, displayTime = ToastThing.LONG
            )
            return EzCollComp.ResultUnmodified
        }

        val text = if (validItems.size == 1) {
            val item = validItems[0]
            val id = if (item.props.isActive) item.title else item.props.email
            StringComp.boldTriple("Eemalda õpilane ", id, "?")
        } else {
            StringComp.boldTriple("Eemalda ", validItems.size.toString(), " õpilast?")
        }

        removeFromCourseModal.setText(text)
        removeFromCourseModal.primaryAction = {
            debug { "Remove confirmed" }

            val (active, pending) = validItems.partition { it.props.isActive }

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

        if (removed) {
            onGroupsChanged()
        }
        return EzCollComp.ResultUnmodified
    }

    private suspend fun sendInvite(item: EzCollComp.Item<StudentProps>): EzCollComp.Result = sendInvite(listOf(item))

    private suspend fun sendInvite(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        val sentCount = if (isMoodleSynced) {
            val pendingMoodleIds = items.filter { !it.props.isActive }.mapNotNull { it.props.utUsername }
            ParticipantsDAO.sendStudentMoodleCourseInvites(courseId, pendingMoodleIds).await()
            pendingMoodleIds.size
        } else {
            val studentEmails = items.map { it.props.email }
            ParticipantsDAO.sendStudentCourseInvites(courseId, studentEmails).await()
            studentEmails.size
        }
        ToastThing("Kutse saadetud $sentCount õpilasele")
        return EzCollComp.ResultUnmodified
    }
}

