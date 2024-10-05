package pages.course_exercise.teacher

import components.code_editor.AdocEditorComp
import components.form.ButtonComp
import components.form.CheckboxComp
import dao.CourseExercisesTeacherDAO
import hide
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.Key
import storage.LocalStore
import stringify
import template
import translation.Str

// Used for both new comment and editing an existing comment
class SubmissionEditCommentComp(
    val courseId: String,
    val courseExId: String,
    val studentId: String,
    val submissionId: String,
    // If not null, then we're editing an existing comment, otherwise new
    val activityId: String?,
    val initialAdoc: String,
    val onCommentEdited: suspend () -> Unit,
    val onCommentCancelled: suspend () -> Unit,
    val startVisible: Boolean,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    @Serializable
    data class FeedbackDraftMapKey(val courseExId: String, val studentId: String, val feedbackDraft: String)

    companion object {
        val draftListSerializer = ListSerializer(FeedbackDraftMapKey.serializer())
    }


    private lateinit var editor: AdocEditorComp
    private lateinit var saveBtn: ButtonComp
    private lateinit var cancelBtn: ButtonComp
    private lateinit var notifyStudentCheckboxComp: CheckboxComp

    private val isNewComment = activityId == null


    override val children: List<Component>
        get() = listOf(editor, saveBtn, cancelBtn, notifyStudentCheckboxComp)

    override fun create() = doInPromise {
        editor = AdocEditorComp(
            initialAdoc,
            placeholder = Str.commentEditorPlaceholder,
            onContentChanged = {
                saveBtn.setEnabled(it.isNotBlank())
                saveDraft(it)
            },
            parent = this
        )

        saveBtn = ButtonComp(
            ButtonComp.Type.FILLED,
            if (isNewComment) Str.doAdd else Str.doEdit,
            onClick = {
                if (activityId == null)
                    CourseExercisesTeacherDAO.addComment(
                        courseId, courseExId, submissionId, editor.getContent(),
                        notifyStudentCheckboxComp.value == CheckboxComp.Value.CHECKED
                    ).await()
                else
                    CourseExercisesTeacherDAO.editComment(
                        courseId, courseExId, submissionId, activityId, editor.getContent(),
                        notifyStudentCheckboxComp.value == CheckboxComp.Value.CHECKED
                    ).await()

                saveDraft(null)

                onCommentEdited()
            },
            parent = this
        )

        cancelBtn = ButtonComp(
            ButtonComp.Type.TEXT,
            Str.cancel,
            onClick = {
                saveDraft(null)
                onCommentCancelled()
            },
            parent = this
        )

        val notificationChecked = LocalStore.get(Key.TEACHER_SEND_COMMENT_NOTIFICATION)
            ?.parseToOrCatch(CheckboxComp.Value.serializer()) ?: CheckboxComp.Value.CHECKED

        notifyStudentCheckboxComp = CheckboxComp(
            Str.doNotifyStudent,
            value = notificationChecked,
            onChange = {
                LocalStore.set(Key.TEACHER_SEND_COMMENT_NOTIFICATION, CheckboxComp.Value.serializer().stringify(it))
            },
            parent = this
        )
    }

    override fun render() = template(
        """
            <ez-comment>
                $editor
                <ez-comment-actions>
                    <ez-flex>
                        <ez-flex style='margin-right: 1rem;'>
                            $saveBtn
                        </ez-flex>
                        $cancelBtn
                    </ez-flex>                
                    $notifyStudentCheckboxComp
                </ez-comment-actions>
            </ez-comment>
        """.trimIndent(),
    )

    override fun postRender() {
        if (!startVisible)
            hide()
    }

    // Drafts are saved automatically
    override fun hasUnsavedChanges() = false

    private fun saveDraft(draft: String?) {
        // Do not save anything if we're editing a comment
        if (!isNewComment)
            return

        val drafts = LocalStore.get(Key.TEACHER_COURSE_EXERCISE_FEEDBACK_DRAFT_LIST)
            ?.parseToOrCatch(draftListSerializer).orEmpty().toMutableList()

        drafts.removeAll { it.courseExId == courseExId && it.studentId == studentId }

        if (!draft.isNullOrBlank())
            drafts.add(FeedbackDraftMapKey(courseExId, studentId, draft))

        LocalStore.set(
            Key.TEACHER_COURSE_EXERCISE_FEEDBACK_DRAFT_LIST,
            draftListSerializer.stringify(drafts)
        )
    }
}