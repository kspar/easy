package pages.course_exercise.teacher

import Key
import LocalStore
import components.form.ButtonComp
import components.form.CheckboxComp
import components.code_editor.AdocEditorComp
import dao.CourseExercisesTeacherDAO
import hide
import kotlinx.coroutines.await
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import stringify
import template
import translation.Str

// use for both new comment and editing an existing comment?
class SubmissionEditCommentComp(
    val courseId: String,
    val courseExId: String,
    val submissionId: String,
    val activityId: String?,
    val initialAdoc: String,
    val initialHtml: String,
    val onCommentEdited: suspend () -> Unit,
    val onCommentCancelled: suspend () -> Unit,
    val startVisible: Boolean,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    private lateinit var editor: AdocEditorComp
    private lateinit var saveBtn: ButtonComp
    private lateinit var cancelBtn: ButtonComp
    private lateinit var notifyStudentCheckboxComp: CheckboxComp

    private val isNew = activityId == null


    override val children: List<Component>
        get() = listOf(editor, saveBtn, cancelBtn, notifyStudentCheckboxComp)

    override fun create() = doInPromise {
        editor = AdocEditorComp(
            initialAdoc,
            placeholder = Str.commentEditorPlaceholder,
            onContentChanged = {
                saveBtn.setEnabled(it.isNotBlank())
            },
            parent = this
        )

        saveBtn = ButtonComp(
            ButtonComp.Type.FILLED,
            if (isNew) Str.doAdd else Str.doEdit,
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

                onCommentEdited()
            },
            parent = this
        )

        cancelBtn = ButtonComp(
            ButtonComp.Type.TEXT,
            Str.cancel,
            onClick = {
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
}