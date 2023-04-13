package pages.participants

import Str
import components.text.StringComp
import components.form.ButtonComp
import components.form.SelectComp
import components.modal.BinaryModalComp
import components.modal.Modal
import debug
import errorMessage
import kotlinx.coroutines.await
import rip.kspar.ezspa.plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class RemoveFromGroupModalComp(
    private val courseId: String,
    private val accessibleGroups: List<ParticipantsRootComp.Group>,
    private val isFor: For,
    private var participants: List<Participant> = emptyList(),
    parent: Component,
) : Component(parent) {

    enum class For { STUDENT, TEACHER }

    data class Participant(
        val studentId: String? = null,
        val pendingStudentEmail: String? = null,
        val teacherId: String? = null,
        val groups: List<ParticipantsRootComp.Group>,
    )


    private val modalComp: BinaryModalComp<Boolean> = BinaryModalComp(
        null, Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { removeFromGroup(groupSelectComp.getValue()) },
        primaryPostAction = ::reinitialise, primaryBtnType = ButtonComp.Type.DANGER,
        defaultReturnValue = false,
        id = if (isFor == For.STUDENT) Modal.REMOVE_STUDENTS_FROM_COURSE_GROUP else Modal.REMOVE_TEACHERS_FROM_COURSE_GROUP,
        parent = this
    )

    private val textComp = StringComp("", modalComp)

    private val groupSelectComp = SelectComp(
        null, emptyList(), true,
        parent = modalComp
    )

    override val children: List<Component>
        get() = listOf(modalComp)

    override fun create() = doInPromise {
        modalComp.setContentComps { listOf(textComp, groupSelectComp) }
    }

    override fun render() = plainDstStr(modalComp.dstId)

    override fun postChildrenBuilt() {
//        groupSelectComp.validateInitial()
    }

    fun openWithClosePromise() = modalComp.openWithClosePromise()

    fun setText(parts: List<StringComp.Part>) {
        textComp.parts = parts
        textComp.rebuild()
    }

    fun setParticipants(participants: List<Participant>): Boolean {
        this.participants = participants

        val participantsGroups = participants.flatMap { it.groups }.toSet()
        val groupOptions = accessibleGroups.intersect(participantsGroups).map {
            SelectComp.Option(it.name, it.id)
        }

        if (groupOptions.isEmpty()) {
            errorMessage { "Pole midagi eemaldada ¯\\_(ツ)_/¯" }
            return false
        }

        groupSelectComp.options = groupOptions
        groupSelectComp.rebuild()
        return true
    }

    private fun reinitialise() {
        groupSelectComp.rebuild()
//        groupSelectComp.validateInitial()
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButton.setEnabled(isFieldValid)
    }

    private suspend fun removeFromGroup(groupId: String?): Boolean {
        if (groupId == null) {
            debug { "No group selected" }
            return false
        }

        debug { "Removing $participants from group $groupId" }

        when (isFor) {
            For.STUDENT -> {
                val (active, pending) = participants.partition { it.studentId != null }
                val activeIds = active.map { it.studentId }
                val pendingEmails = pending.map { it.pendingStudentEmail }
                debug { "Removing active students $activeIds and pending students $pendingEmails from group $groupId" }

                val body = mapOf(
                    "active_students" to activeIds.map { mapOf("id" to it) },
                    "pending_students" to pendingEmails.map { mapOf("email" to it) }
                )

                fetchEms("/courses/$courseId/groups/$groupId/students", ReqMethod.DELETE, body,
                    successChecker = { http200 }).await()

            }
            For.TEACHER -> {
                val teacherIds = participants.map { it.teacherId }
                debug { "Removing teachers $teacherIds from group $groupId" }

                val body = mapOf(
                    "teachers" to teacherIds.map { mapOf("id" to it) }
                )

                fetchEms("/courses/$courseId/groups/$groupId/teachers", ReqMethod.DELETE, body,
                    successChecker = { http200 }).await()

            }
        }

        successMessage { "Eemaldatud" }
        return true
    }
}