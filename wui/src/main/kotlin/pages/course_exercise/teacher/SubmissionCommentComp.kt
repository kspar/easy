package pages.course_exercise.teacher

import EzDate
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

abstract class SubmissionCommentContentComp(parent: Component) : Component(parent)

class SubmissionCommentComp(
    val courseId: String,
    val courseExerciseId: String,
    val submissionId: String,
    val submissionNumber: Int,
    val activityId: String,
    val authorFirstName: String,
    val authorLastName: String,
    val createdAt: EzDate,
    val editedAt: EzDate?,
    var commentAdoc: String,
    var commentHtml: String,
    val grade: Int?,
    val isEditable: Boolean,
    var isEditing: Boolean,
    val onActivitiesChanged: suspend () -> Unit,
    parent: Component
) : Component(parent) {

    private lateinit var commentContent: SubmissionCommentContentComp
    private var menu: DropdownIconMenuComp? = null
    private lateinit var deleteConfirmationModal: ConfirmationTextModalComp

    override val children: List<Component>
        get() = listOfNotNull(commentContent, menu, deleteConfirmationModal)

    override fun create() = doInPromise {
        commentContent = if (isEditing)
            SubmissionEditCommentComp(
                courseId, courseExerciseId, submissionId, activityId, commentAdoc, commentHtml,
                onCommentEdited = onActivitiesChanged,
                onCommentCancelled = {
                    isEditing = false
                    createAndBuild().await()
                },
                startVisible = true,
                parent = this
            )
        else
            SubmissionViewCommentComp(grade, commentHtml, parent = this)

        menu = if (!isEditing && isEditable)
            DropdownIconMenuComp(
                Icons.dotsVertical, Str.doEdit, buildList {
                    add(
                        DropdownMenuComp.Item(
                            if (commentAdoc.isBlank()) Str.addComment else Str.editComment,
                            Icons.edit, {
                                isEditing = true
                                createAndBuild().await()
                            })
                    )
                    if (commentAdoc.isNotBlank()) {
                        add(DropdownMenuComp.Item(Str.deleteComment, Icons.delete, {
                            val commentDeleted = deleteConfirmationModal.openWithClosePromise().await()
                            if (commentDeleted)
                                onActivitiesChanged()
                        }))
                    }
                },
                parent = this
            )
        else null

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
            <ez-flow-item>
                <ez-avatar>{{teacherInitials}}</ez-avatar>
                <ez-flow-item-content>
                    <ez-flow-item-header>
                        <ez-flow-item-header-left>
                            <ez-flow-item-header-title>
                                {{teacherName}}
                            </ez-flow-item-header-title>
                            <ez-flow-item-header-secondary>
                                <span style='margin-right: .5rem;' title='{{timeFull}}{{#edited}} ({{editedLabel}} {{editedAt}}){{/edited}}'>
                                    {{time}}
                                </span>
                                · 
                                <span style='margin-left: .5rem;'>{{submissionLabel}} # {{subNum}}</span>
                            </ez-flow-item-header-secondary>
                        </ez-flow-item-header-left>
                        
                        ${menu.dstIfNotNull()}
                    </ez-flow-item-header>
                    
                    $commentContent
                </ez-ez-flow-item-content>
            </ez-flow-item>
        """.trimIndent(),
        "teacherName" to "$authorFirstName $authorLastName",
        "teacherInitials" to "${authorFirstName.first()}${authorLastName.first()}",
        "time" to createdAt.toHumanString(EzDate.Format.DATE),
        "timeFull" to createdAt.toHumanString(EzDate.Format.FULL),
        "edited" to editedAt?.let {
            mapOf("editedAt" to maxOf(editedAt, createdAt).toHumanString(EzDate.Format.FULL))
        },
        "editedLabel" to Str.editedAt,
        "subNum" to submissionNumber,
        "submissionLabel" to Str.submission,
    )
}
