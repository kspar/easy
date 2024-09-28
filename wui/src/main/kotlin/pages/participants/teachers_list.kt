package pages.participants

import EzDate
import Icons
import components.ezcoll.EzCollComp
import components.form.ButtonComp
import components.modal.ConfirmationTextModalComp
import components.text.StringComp
import dao.ParticipantsDAO
import debug
import kotlinx.coroutines.await
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage
import translation.Str


class ParticipantsTeachersListComp(
    private val courseId: String,
    private val teachers: List<ParticipantsDAO.Teacher>,
    private val onGroupsChanged: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    data class TeacherProps(
        val firstName: String, val lastName: String,
        val email: String, val username: String, val createdAt: EzDate?,
    )

    private lateinit var teachersColl: EzCollComp<TeacherProps>
    private lateinit var removeFromCourseModal: ConfirmationTextModalComp

    override val children: List<Component>
        get() = listOf(teachersColl, removeFromCourseModal)

    override fun create() = doInPromise {
        val props = teachers.map {
            TeacherProps(
                it.given_name,
                it.family_name,
                it.email,
                it.id,
                it.created_at,
            )
        }

        val items = props.sortedWith(
            compareBy<TeacherProps> { it.lastName.lowercase() }.thenBy { it.firstName.lowercase() }
        ).map { p ->
            val actions = listOf(
                EzCollComp.Action(Icons.removeParticipant, Str.removeFromCourse, onActivate = ::removeFromCourse),
            )

            EzCollComp.Item(
                p,
                EzCollComp.ItemTypeIcon(Icons.teacher),
                "${p.firstName} ${p.lastName}",
                bottomAttrs = listOfNotNull(
                    EzCollComp.SimpleAttr(Str.email, p.email, Icons.emailUnf),
                    EzCollComp.SimpleAttr(Str.username, p.username, Icons.userUnf),
                ),
                actions = actions
            )
        }

        teachersColl = EzCollComp(
            items,
            EzCollComp.Strings(Str.teachersSingular, Str.teachersPlural),
            parent = this,
        )

        removeFromCourseModal = ConfirmationTextModalComp(
            null, Str.doRemove, Str.cancel, Str.removing,
            primaryBtnType = ButtonComp.Type.FILLED_DANGER, parent = this
        )
    }


    private suspend fun removeFromCourse(item: EzCollComp.Item<TeacherProps>) =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<TeacherProps>>): EzCollComp.Result {
        debug { "Removing teachers ${items.map { it.title }}?" }

        val text = if (items.size == 1)
            StringComp.boldTriple("${Str.doRemove} ", items[0].title, "?")
        else
            StringComp.boldTriple("${Str.doRemove} ", items.size.toString(), " ${Str.teachersPlural}?")

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

            successMessage { Str.removed }

            true
        }

        val removed = removeFromCourseModal.openWithClosePromise().await()

        if (removed) {
            onGroupsChanged()
        }
        return EzCollComp.ResultUnmodified
    }
}