package pages.course_exercise.student

import Icons
import components.ToastThing
import components.code_editor.old.OldCodeEditorComp
import components.form.OldButtonComp
import components.text.WarningComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import hide
import kotlinx.coroutines.await
import observeValueChange
import org.w3c.files.Blob
import pages.course_exercise.AutogradeLoaderComp
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import pages.course_exercise.teacher.SubmissionCommentsListComp
import pages.course_exercise.teacher.SubmissionGradeComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
import saveAsFile
import show
import template
import translation.Str
import uploadFile


class CourseExerciseStudentSubmitTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val graderType: ExerciseDAO.GraderType,
    private val isOpenForSubmissions: Boolean,
    private val solutionFileName: String,
    private val solutionFileType: ExerciseDAO.SolutionFileType,
    private val onNewSubmission: () -> Unit,
    parent: Component
) : Component(parent) {

    private data class EditorContent(val content: String, val isDraft: Boolean)

    private lateinit var editor: OldCodeEditorComp
    private lateinit var syncIcon: CourseExerciseEditorStatusComp
    private lateinit var submitBtn: OldButtonComp
    private val warning = WarningComp(parent = this)
    private lateinit var grade: SubmissionGradeComp
    private lateinit var testsFeedback: ExerciseAutoFeedbackHolderComp
    private lateinit var autogradeLoader: AutogradeLoaderComp
    private lateinit var commentsList: SubmissionCommentsListComp

    private var isAutogradeInProgressInitial = false

    private val syncFailToastId = IdGenerator.nextId()
    private var syncFailToast: ToastThing? = null

    var currentSubmission: String? = null
    var isDraft = false
    var hasUnsavedDraft = false


    override val children: List<Component>
        get() = listOf(editor, syncIcon, warning, submitBtn, grade, testsFeedback, autogradeLoader, commentsList)

    override fun create() = doInPromise {
        val submissionP = CourseExercisesStudentDAO.getLatestSubmission(courseId, courseExId)
        val draft = CourseExercisesStudentDAO.getSubmissionDraft(courseId, courseExId).await()
        val submission = submissionP.await()
        isAutogradeInProgressInitial =
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.IN_PROGRESS

        currentSubmission = submission?.solution

        val content = when {
            submission == null && draft != null -> {
                isDraft = true
                EditorContent(draft.solution, true)
            }

            submission != null && draft == null -> {
                isDraft = false
                EditorContent(submission.solution, false)
            }

            submission != null && draft != null -> {
                when {
                    // it's an earlier draft or it has the same contents (but may be saved later)
                    draft.created_at <= submission.submission_time || draft.solution == submission.solution -> {
                        isDraft = false
                        EditorContent(submission.solution, false)
                    }

                    else -> {
                        isDraft = true
                        EditorContent(draft.solution, true)
                    }
                }
            }

            else -> {
                null
            }
        }

        editor = OldCodeEditorComp(
            OldCodeEditorComp.File(
                solutionFileName, content?.content,
                editability = if (isOpenForSubmissions) OldCodeEditorComp.Edit.EDITABLE else OldCodeEditorComp.Edit.READONLY
            ),
            placeholder = Str.solutionEditorPlaceholder, parent = this
        )

        syncIcon = CourseExerciseEditorStatusComp(
            "", CourseExerciseEditorStatusComp.Status.IN_SYNC,
            canUpload = isOpenForSubmissions,
            onUpload = ::uploadSolution,
            onDownload = ::downloadSolution,
            parent = this
        )

        submitBtn = OldButtonComp(
            OldButtonComp.Type.PRIMARY,
            if (graderType == ExerciseDAO.GraderType.AUTO) Str.doSubmitAndCheck else Str.doSubmit,
            if (graderType == ExerciseDAO.GraderType.AUTO) Icons.robot else null,
            isEnabledInitial = isOpenForSubmissions,
            onClick = {
                try {
                    setEditorEditable(false)
                    if (!isAutogradeInProgressInitial)
                        submit(editor.getActiveTabContent()!!)
                    isAutogradeInProgressInitial = false
                    testsFeedback.clear()
                    if (graderType == ExerciseDAO.GraderType.AUTO) {
                        grade.hide()
                        awaitAutograde()
                        grade.show()
                    }
                    onNewSubmission()
                } finally {
                    setEditorEditable(true)
                }
            },
            clickedLabel = if (graderType == ExerciseDAO.GraderType.AUTO) Str.autoAssessing else Str.saving,
            parent = this
        )

        grade = SubmissionGradeComp(submission?.grade, null, parent = this)

        testsFeedback = ExerciseAutoFeedbackHolderComp(
            submission?.auto_assessment?.feedback,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED,
            false,
            this
        )
        autogradeLoader = AutogradeLoaderComp(false, this)

        commentsList = SubmissionCommentsListComp(courseId, courseExId, null, parent = this)
    }

    override fun render() = template(
        """
            <div class='student-submit-editor' style="position: relative">
                $syncIcon
                $editor
            </div>
            <div id='${submitBtn.dstId}' style='display: flex; justify-content: center; margin-top: 3rem;'></div>
            $warning
            $autogradeLoader
            $grade
            $testsFeedback
            $commentsList
        """.trimIndent(),
    )

    override fun postRender() {
        if (currentSubmission == null)
            grade.hide()

        doInPromise {
            observeValueChange(1000, 500,
                valueProvider = { editor.getActiveTabContent()!! },
                action = ::saveDraft,
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                idleCallback = {
                    // Race condition: if changed and submitted before this is called, then it will update to isDraft even though it's been submitted
                    val isDistinctDraft = editor.getActiveTabContent() != currentSubmission
                    updateStatus(CourseExerciseEditorStatusComp.Status.WAITING, isDistinctDraft)
                    hasUnsavedDraft = isDistinctDraft
                })
        }
    }

    override fun postChildrenBuilt() {
        updateStatus(CourseExerciseEditorStatusComp.Status.IN_SYNC, isDraft)
        if (isAutogradeInProgressInitial)
            submitBtn.click()
        if (!isOpenForSubmissions) {
            submitBtn.hide()
            warning.setMsg(Str.exerciseClosedForSubmissions)
        }
    }

    private suspend fun saveDraft(content: String, retryCount: Int = 0) {
        if (retryCount > 2) {
            syncFailToast = ToastThing(
                Str.draftSaveFailedMsg,
                ToastThing.Action(Str.tryAgain, { saveDraft(content) }),
                icon = ToastThing.ERROR, displayTime = ToastThing.PERMANENT, id = syncFailToastId
            )
            updateStatus(CourseExerciseEditorStatusComp.Status.SYNC_FAILED)
            hasUnsavedDraft = true
            return
        }

        updateStatus(CourseExerciseEditorStatusComp.Status.SYNCING)

        try {
            CourseExercisesStudentDAO.postSubmissionDraft(courseId, courseExId, content).await()
        } catch (e: Throwable) {
            saveDraft(content, retryCount + 1)
            return
        }

        updateStatus(CourseExerciseEditorStatusComp.Status.IN_SYNC)
        hasUnsavedDraft = false
        syncFailToast?.dismiss()
    }

    private fun updateStatus(status: CourseExerciseEditorStatusComp.Status, isDraft: Boolean? = null) {
        if (isDraft != null)
            syncIcon.msg = when {
                isDraft -> Str.solutionEditorStatusDraft
                else -> Str.solutionEditorStatusSubmission
            }
        syncIcon.status = status
        syncIcon.rebuild()
    }

    private suspend fun submit(solution: String) {
        currentSubmission = solution
        CourseExercisesStudentDAO.postSubmission(courseId, courseExId, solution).await()
        updateStatus(CourseExerciseEditorStatusComp.Status.IN_SYNC, false)
        ToastThing(Str.submitSuccessMsg)
    }

    private suspend fun awaitAutograde() {
        // Make students wait for a multiple of 5 seconds for animation to finish :D
        // Repeat animation and poll whether the promise has resolved every 5 seconds
        var autoassessFinished = false
        CourseExercisesStudentDAO.awaitAutograde(courseId, courseExId).then {
            autoassessFinished = true
        }

        autogradeLoader.runUntil { !autoassessFinished }

        val submission = CourseExercisesStudentDAO.getLatestSubmission(courseId, courseExId).await()

        grade.setGrade(submission?.grade)
        testsFeedback.setFeedback(
            submission?.auto_assessment?.feedback,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED
        )

        // Repeating this status to mitigate the edit-submit race condition
        updateStatus(CourseExerciseEditorStatusComp.Status.IN_SYNC, false)
    }

    private fun uploadSolution() {
        uploadFile {
            editor.setFileValue(editor.getActiveTabFilename()!!, it.content)
        }
    }

    private fun downloadSolution() {
        Blob(listOf(editor.getActiveTabContent()!!).toTypedArray()).saveAsFile(solutionFileName)
    }

    private fun setEditorEditable(editable: Boolean) {
        editor.setFileEditable(editor.getActiveTabFilename()!!, editable)
    }

    override fun hasUnsavedChanges() = hasUnsavedDraft
}
