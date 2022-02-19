package pages.participants

import Icons
import components.EzCollComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import debug
import errorMessage
import kotlinx.coroutines.await
import plainDstStr
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

// buildList is experimental
@ExperimentalStdlibApi
class ParticipantsStudentsListComp(
    private val courseId: String,
    private val students: List<ParticipantsRootComp.Student>,
    private val studentsPending: List<ParticipantsRootComp.PendingStudent>,
    private val studentsMoodlePending: List<ParticipantsRootComp.PendingMoodleStudent>,
    private val groups: List<ParticipantsRootComp.Group>,
    private val isEditable: Boolean,
    parent: Component?
) : Component(parent) {

    data class StudentProps(
        val firstName: String?, val lastName: String?,
        val email: String, val username: String?, val utUsername: String?,
        val isActive: Boolean, val groups: List<String>
    )

    private lateinit var studentsColl: EzCollComp<StudentProps>

    private lateinit var removeFromCourseModal: ConfirmationTextModalComp

    override val children: List<Component>
        get() = listOf(studentsColl, removeFromCourseModal)

    override fun create() = doInPromise {

        val activeStudentProps = students.map {
            StudentProps(
                it.given_name,
                it.family_name,
                it.email,
                it.id,
                it.moodle_username,
                true,
                // maybe should include ids as well for removing groups
                it.groups.map { it.name })
        }
        val pendingStudentProps = studentsPending.map {
            StudentProps(null, null, it.email, null, null, false, it.groups.map { it.name })
        }
        val moodlePendingStudentProps = studentsMoodlePending.map {
            StudentProps(null, null, it.email, null, it.moodle_username, false, it.groups.map { it.name })
        }

        val studentProps = activeStudentProps + pendingStudentProps + moodlePendingStudentProps

        val hasGroups = groups.isNotEmpty()
        val groupNames = groups.map { it.name }.sorted()

        val items = studentProps.map { p ->
            EzCollComp.Item(
                p,
                EzCollComp.ItemTypeIcon(if (p.isActive) Icons.user else Icons.pending),
                if (p.isActive) "${p.firstName} ${p.lastName}" else "(Kutse ootel)",
                if (p.isActive) EzCollComp.TitleStatus.NORMAL else EzCollComp.TitleStatus.INACTIVE,
                topAttr = if (hasGroups) EzCollComp.ListAttr(
                    "Rühmad",
                    p.groups.map { EzCollComp.ListAttrItem(it) }.toMutableList(),
                    Icons.groups,
//                    onClick = if (isEditable) ::changeGroups else null
                ) else null,
                bottomAttrs = buildList<EzCollComp.Attr<StudentProps>> {
                    add(EzCollComp.SimpleAttr("Email", p.email, Icons.email))
                    p.username?.let { add(EzCollComp.SimpleAttr("Kasutajanimi", p.username, Icons.user)) }
                    p.utUsername?.let { add(EzCollComp.SimpleAttr("UT kasutajanimi", p.utUsername, Icons.utUser)) }
                },
                isSelectable = isEditable,
                actions = if (isEditable) listOf(
                    // TODO: add to group and remove from group, same modal as mass action
//                    EzCollComp.Action(Icons.groups, "Rühmad...", onActivate = ::changeGroups),
                    EzCollComp.Action(Icons.removeParticipant, "Eemalda kursuselt", onActivate = ::removeFromCourse),
                ) else emptyList(),
            )
        }

        val massActions = if (isEditable) buildList {
            if (hasGroups) {
                add(
                    EzCollComp.MassAction<StudentProps>(Icons.addToGroup, "Lisa rühma", { TODO() })
                )
                add(
                    EzCollComp.MassAction<StudentProps>(Icons.removeFromGroup, "Eemalda rühmast", { TODO() })
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
                                    groupNames.map { g ->
                                        EzCollComp.Filter(g) { it.props.groups.contains(g) }
                                    }
                        )
                    )
            },
            sorters = buildList {
                if (hasGroups)
                    add(
                        EzCollComp.Sorter("Rühma ja nime järgi",
                            compareBy<EzCollComp.Item<StudentProps>> { it.props.groups.getOrNull(0) }
                                .thenBy { it.props.groups.getOrNull(1) }
                                .thenBy { it.props.groups.getOrNull(2) }
                                .thenBy { it.props.groups.getOrNull(3) }
                                .thenBy { it.props.groups.getOrNull(4) }
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
            primaryBtnType = ButtonComp.Type.DANGER, parent = this
        )
    }

    override fun render() = plainDstStr(studentsColl.dstId, removeFromCourseModal.dstId)


    private suspend fun removeFromCourse(item: EzCollComp.Item<StudentProps>): EzCollComp.Result =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        debug { "Removing students ${items.map { it.title }}?" }

        val text = if (items.size == 1) {
            val item = items[0]
            val id = if (item.props.isActive) item.title else item.props.email
            "Eemalda õpilane $id?"
        } else {
            "Eemalda ${items.size} õpilast?"
        }

        removeFromCourseModal.text = text
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

            true
        }

        val removed = removeFromCourseModal.openWithClosePromise().await()

        return if (removed)
            EzCollComp.ResultModified<StudentProps>(emptyList())
        else
            EzCollComp.ResultUnmodified
    }
}

