package pages.course_exercise.student

import Auth
import Icons
import components.ToastThing
import components.code_editor.CodeEditorComp
import components.dropdown.DropdownMenuComp
import components.form.ButtonComp
import components.text.WarningComp
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import hide
import kotlinx.coroutines.await
import nowTimestamp
import observeValueChange
import pages.course_exercise.AutogradeLoaderComp
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import pages.course_exercise.teacher.SubmissionCommentsListComp
import pages.course_exercise.teacher.SubmissionGradeComp
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getElemByIdOrNull
import saveTextAsFile
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

    private lateinit var editor: CodeEditorComp
    private lateinit var submitBtn: ButtonComp
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
        get() = listOf(editor, warning, submitBtn, grade, testsFeedback, autogradeLoader, commentsList)

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

        editor = CodeEditorComp(
            listOf(
                CodeEditorComp.File(
                    solutionFileName, content?.content,
                    isEditable = isOpenForSubmissions,
                )
            ),
            placeholder = Str.solutionEditorPlaceholder,
            menuOptions = buildList {
                if (isOpenForSubmissions)
                    add(
                        DropdownMenuComp.Item(
                            Str.uploadSubmission, Icons.upload, onSelected = {
                                uploadFile { editor.setContent(it.content) }
                            }
                        ))
                add(
                    DropdownMenuComp.Item(
                        Str.downloadSubmission, Icons.download, onSelected = {
                            saveTextAsFile("${courseExId}_${nowTimestamp()}_$solutionFileName", editor.getContent())
                        }
                    ))
            },
            parent = this
        )

        submitBtn = ButtonComp(
            ButtonComp.Type.FILLED,
            if (graderType == ExerciseDAO.GraderType.AUTO) Str.doSubmitAndCheck else Str.doSubmit,
            if (graderType == ExerciseDAO.GraderType.AUTO) Icons.robot else null,
            clickedLabel = if (graderType == ExerciseDAO.GraderType.AUTO) Str.autoAssessing else Str.saving,
            onClick = {
                try {
                    setEditorEditable(false)
                    if (!isAutogradeInProgressInitial)
                        submit(editor.getContent())
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
                    updateStatus(syncSuccess = true, isDraft = false)
                }
            },
            parent = this
        )

        grade = SubmissionGradeComp(submission?.grade, null, parent = this)

        testsFeedback = ExerciseAutoFeedbackHolderComp(
            submission?.auto_assessment?.feedback,
            submission?.autograde_status == CourseExercisesStudentDAO.AutogradeStatus.FAILED,
            canRetry = false, isOpen = false,
            parent = this
        )
        autogradeLoader = AutogradeLoaderComp(false, this)

        commentsList = SubmissionCommentsListComp(courseId, courseExId, Auth.username!!, null, parent = this)
    }

    override fun render() = template(
        """
            <div style='margin-top: 3rem;'>
                $editor
            </div>
            <ez-flex style='justify-content: center; margin-top: 3rem;'>
                $submitBtn
            </ez-flex>
            $warning
            $autogradeLoader
            $testsFeedback
            $grade
            $commentsList
        """.trimIndent(),
    )

    override fun postChildrenBuilt() {
        if (currentSubmission == null)
            grade.hide()

        doInPromise {
            observeValueChange(1000, 500,
                valueProvider = { editor.getContent() },
                action = ::saveDraft,
                continuationConditionProvider = { getElemByIdOrNull(editor.dstId) != null },
                idleCallback = {
                    // Race condition: if changed and submitted before this is called, then it will update to isDraft even though it's been submitted
                    val isDistinctDraft = editor.getContent() != currentSubmission
                    updateStatus(isDraft = isDistinctDraft)
                    hasUnsavedDraft = isDistinctDraft
                })
        }

        updateStatus(syncSuccess = true, isDraft)
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
                icon = ToastThing.ERROR_INFO, displayTime = ToastThing.PERMANENT, id = syncFailToastId
            )
            updateStatus(syncSuccess = false)
            hasUnsavedDraft = true
            return
        }

        try {
            CourseExercisesStudentDAO.postSubmissionDraft(courseId, courseExId, content).await()
        } catch (e: Throwable) {
            saveDraft(content, retryCount + 1)
            return
        }

        updateStatus(syncSuccess = true)
        hasUnsavedDraft = false
        syncFailToast?.dismiss()
    }

    private fun updateStatus(syncSuccess: Boolean? = null, isDraft: Boolean? = null) {
        isDraft?.let {
            editor.setStatusText(if (isDraft) Str.solutionEditorStatusDraft else Str.solutionEditorStatusSubmission)
        }

        syncSuccess?.let {
            editor.setStatusIcon(if (syncSuccess) Icons.cloudSuccess else Icons.cloudFail)
        }
    }

    private suspend fun submit(solution: String) {
        currentSubmission = solution
        CourseExercisesStudentDAO.postSubmission(courseId, courseExId, solution).await()
        updateStatus(syncSuccess = true, isDraft = false)
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
    }

    private suspend fun setEditorEditable(editable: Boolean) {
        editor.setFileProps(editable = editable)
        editor.setActionsEnabled(editable)
    }

    override fun hasUnsavedChanges() = hasUnsavedDraft
}
