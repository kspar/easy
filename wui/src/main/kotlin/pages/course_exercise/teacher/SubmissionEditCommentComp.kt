package pages.course_exercise.teacher

import Key
import LocalStore
import components.ButtonComp
import components.CheckboxComp
import components.code_editor.CodeEditorComp
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import hide
import kotlinx.coroutines.await
import observeValueChange
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
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

    private lateinit var editor: CodeEditorComp
    private lateinit var saveBtn: ButtonComp
    private lateinit var cancelBtn: ButtonComp
    private lateinit var notifyStudentCheckboxComp: CheckboxComp
    private lateinit var preview: AdocCommentPreviewComp

    private val isNew = activityId == null

    private val editorFilename = "comment.adoc"

    override val children: List<Component>
        get() = listOf(editor, saveBtn, cancelBtn, notifyStudentCheckboxComp, preview)

    override fun create() = doInPromise {
        editor = CodeEditorComp(
            CodeEditorComp.File(editorFilename, initialAdoc),
            softWrap = true, showLineNumbers = false, showTabs = false,
            placeholder = Str.commentEditorPlaceholder,
            parent = this
        )

        saveBtn = ButtonComp(
            ButtonComp.Type.FILLED,
            if (isNew) Str.doAdd else Str.doEdit,
            onClick = {
                if (activityId == null)
                    CourseExercisesTeacherDAO.addComment(
                        courseId, courseExId, submissionId, getEditorValue(),
                        notifyStudentCheckboxComp.value == CheckboxComp.Value.CHECKED
                    ).await()
                else
                    CourseExercisesTeacherDAO.editComment(
                        courseId, courseExId, submissionId, activityId, getEditorValue(),
                        notifyStudentCheckboxComp.value == CheckboxComp.Value.CHECKED
                    ).await()

                onCommentEdited()
            },
            disableOnClick = true,
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

        preview = AdocCommentPreviewComp(this)
    }

    override fun render() = template(
        """
            $editor
            $saveBtn
            $cancelBtn
            $notifyStudentCheckboxComp
            $preview
        """.trimIndent(),
    )

    override fun postRender() {
        if (!startVisible)
            hide()
    }

    override fun postChildrenBuilt() {
        doInPromise {
            observeValueChange(
                200, 200, doActionFirst = true,
                valueProvider = { getEditorValue() },
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                action = { newValue ->
                    saveBtn.setEnabled(newValue.isNotBlank())
                    preview.contentHtml = ExerciseDAO.previewAdocContent(newValue).await()
                },
            )
        }
    }

    private fun getEditorValue() = editor.getFileValue(editorFilename).trim()
}
