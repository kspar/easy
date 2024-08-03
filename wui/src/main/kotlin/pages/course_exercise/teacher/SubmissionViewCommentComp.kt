package pages.course_exercise.teacher

import Icons
import components.DropdownIconMenuComp
import components.DropdownMenuComp
import components.form.OldButtonComp
import components.modal.ConfirmationTextModalComp
import dao.CourseExercisesTeacherDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template
import translation.Str


class SubmissionViewCommentComp(
    val courseId: String,
    val courseExerciseId: String,
    val submissionId: String,
    val submissionNumber: Int,
    val activityId: String,
    val html: String,
    val isEditable: Boolean,
    val onStartEditing: suspend () -> Unit,
    val onCommentDeleted: suspend () -> Unit,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    private var menu: DropdownIconMenuComp? = null
    private lateinit var deleteConfirmationModal: ConfirmationTextModalComp

    override val children: List<Component>
        get() = listOfNotNull(menu, deleteConfirmationModal)

    override fun create() = doInPromise {
        if (isEditable) {
            menu = DropdownIconMenuComp(
                Icons.dotsVertical, Str.doEdit, buildList {
                    add(DropdownMenuComp.Item(Str.doEdit, Icons.edit, {
                        onStartEditing()
                    }))
                    if (html.isNotBlank()) {
                        add(DropdownMenuComp.Item(Str.doDelete, Icons.delete, {
                            val commentDeleted = deleteConfirmationModal.openWithClosePromise().await()
                            if (commentDeleted)
                                onCommentDeleted()
                        }))
                    }
                },
                parent = this
            )
        }

        deleteConfirmationModal = ConfirmationTextModalComp(
            Str.deleteComment, Str.doDelete, Str.cancel, Str.deleting,
            primaryAction = {
                CourseExercisesTeacherDAO.deleteComment(courseId, courseExerciseId, submissionId, activityId).await()
                true
            },
            primaryBtnType = OldButtonComp.Type.DANGER, parent = this
        )
    }

    override fun render() = template(
        """
            {{submissionLabel}} #{{subNum}} - {{{html}}}
            ${menu.dstIfNotNull()}
        """.trimIndent(),
        "subNum" to submissionNumber,
        "html" to html,
        "submissionLabel" to Str.submission
    )
}
