package pages.participants

import Str
import components.StringComp
import components.form.SelectComp
import components.modal.BinaryModalComp
import components.modal.Modal
import debug
import kotlinx.coroutines.await
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage

class AddToGroupModalComp(
    private val courseId: String,
    private val availableGroups: List<ParticipantsRootComp.Group>,
    private val isFor: For,
    var participants: List<Participant> = emptyList(),
    parent: Component,
) : Component(parent) {

    enum class For { STUDENT, TEACHER }

    data class Participant(
        val studentId: String? = null,
        val pendingStudentEmail: String? = null,
        val teacherId: String? = null,
    )

    data class AddedGroup(
        val id: String,
        val name: String,
    )

    private val modalComp: BinaryModalComp<AddedGroup?> = BinaryModalComp(
        null, Str.doSave(), Str.cancel(), Str.saving(),
        primaryAction = { groupSelectComp.getLabelAndValue().let { addToGroup(it.first, it.second) } },
        primaryPostAction = ::reinitialise,
        defaultReturnValue = null,
        id = if (isFor == For.STUDENT) Modal.ADD_STUDENTS_TO_COURSE_GROUP else Modal.ADD_TEACHERS_TO_COURSE_GROUP,
        parent = this
    )

    private val textComp = StringComp("", modalComp)

    // Ideally should be validatable, so we could disable primary btn when empty is selected
    private val groupSelectComp = SelectComp(
        null, availableGroups.map { SelectComp.Option(it.name, it.id) }, true,
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

    private fun reinitialise() {
        groupSelectComp.rebuild()
//        groupSelectComp.validateInitial()
    }

    private fun updateSubmitBtn(isFieldValid: Boolean) {
        modalComp.primaryButton.setEnabled(isFieldValid)
    }

    private suspend fun addToGroup(groupName: String?, groupId: String?): AddedGroup? {
        if (groupId == null || groupName == null) {
            debug { "No group selected" }
            return null
        }

        debug { "Adding $participants to group $groupName ($groupId)" }

        when (isFor) {
            For.STUDENT -> {
                val (active, pending) = participants.partition { it.studentId != null }
                val activeIds = active.map { it.studentId }
                val pendingEmails = pending.map { it.pendingStudentEmail }
                debug { "Adding active students $activeIds and pending students $pendingEmails to group $groupId" }

                val body = mapOf(
                    "active_students" to activeIds.map { mapOf("id" to it) },
                    "pending_students" to pendingEmails.map { mapOf("email" to it) }
                )

                fetchEms("/courses/$courseId/groups/$groupId/students", ReqMethod.POST, body,
                    successChecker = { http200 }).await()

            }
            For.TEACHER -> {
                val teacherIds = participants.map { it.teacherId }
                debug { "Adding teachers $teacherIds to group $groupId" }

                val body = mapOf(
                    "teachers" to teacherIds.map { mapOf("id" to it) }
                )

                fetchEms("/courses/$courseId/groups/$groupId/teachers", ReqMethod.POST, body,
                    successChecker = { http200 }).await()

            }
        }

        successMessage { "Lisatud" }
        return AddedGroup(groupId, groupName)
    }
}