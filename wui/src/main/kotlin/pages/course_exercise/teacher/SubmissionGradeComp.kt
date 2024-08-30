package pages.course_exercise.teacher

import Icons
import storage.Key
import storage.LocalStore
import components.form.CheckboxComp
import components.form.IconButtonComp
import components.form.MdGradeFieldExperimentComp
import dao.CourseExercisesTeacherDAO
import hide
import kotlinx.coroutines.await
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import show
import stringify
import template
import translation.Str


class SubmissionGradeComp(
    private var initialGrade: CourseExercisesTeacherDAO.Grade?,
    // editable if non-null
    private val gradeEdit: GradeEdit?,
    parent: Component
) : SubmissionCommentContentComp(parent) {

    data class GradeEdit(
        val courseId: String,
        val courseExerciseId: String,
        val submissionId: String,
        val onGradeSaved: suspend () -> Unit,
    )

    private var pointsField: MdGradeFieldExperimentComp? = null
    private lateinit var saveBtn: IconButtonComp
    private lateinit var cancelBtn: IconButtonComp
    private lateinit var notifyStudentCheckbox: CheckboxComp

    override val children: List<Component>
        get() = listOfNotNull(pointsField, saveBtn, cancelBtn, notifyStudentCheckbox)

    override fun create() = doInPromise {
        if (gradeEdit != null)
            pointsField = MdGradeFieldExperimentComp(
                Str.gradeFieldLabel,
                initialValue = initialGrade?.grade,
                onValueChange = {
                    saveBtn.show()
                    cancelBtn.show()
                    notifyStudentCheckbox.show()
                },
                onENTER = { saveGrade() }
            )

        saveBtn = IconButtonComp(
            Icons.check, Str.doSave,
            onClick = { saveGrade() },
            disableOnClick = true,
            parent = this
        )

        cancelBtn = IconButtonComp(
            Icons.close, Str.cancel,
            onClick = {
                createAndBuild().await()
            },
            disableOnClick = true,
            parent = this
        )

        val notificationChecked = LocalStore.get(Key.TEACHER_SEND_GRADE_NOTIFICATION)
            ?.parseToOrCatch(CheckboxComp.Value.serializer()) ?: CheckboxComp.Value.CHECKED

        notifyStudentCheckbox = CheckboxComp(
            Str.doNotifyStudent,
            value = notificationChecked,
            onChange = {
                LocalStore.set(Key.TEACHER_SEND_GRADE_NOTIFICATION, CheckboxComp.Value.serializer().stringify(it))
            },
            parent = this
        )
    }

    override fun render() = template(
        """
            <h5 style='margin-top: 3rem; margin-bottom: 5rem;'>
                <ez-flex>
                    {{gradeLabel}}:
                    {{#editable}}
                        <ez-inline-flex style='margin-left: 1rem; 
                            --md-outlined-text-field-bottom-space: 10px;
                            --md-outlined-text-field-top-space: 10px;
                            --md-outlined-text-field-focus-outline-width: 2px;
                        '>$pointsField</ez-inline-flex>
                    {{/editable}} 
                    {{^editable}}
                        <ez-grade-badge style='margin-left: 1rem;'>
                            {{points}} / 100
                        </ez-grade-badge>
                    {{/editable}}
                    
                    <ez-flex style='margin-left: 1.5rem; align-items: start;' {{#indirectGrade}}title='{{pastGradeHelp}}'{{/indirectGrade}}>
                        <ez-flex class='icon-med'>{{{graderIcon}}}</ez-flex>
                        {{#indirectGrade}}<ez-flex class='icon-small'>{{{graderPastIcon}}}</ez-flex>{{/indirectGrade}}
                    </ez-flex>
                    <ez-flex style='margin-left: 2rem;'>
                        $saveBtn
                        $cancelBtn
                        $notifyStudentCheckbox
                    </ez-flex>
                </ez-flex>
            </h5>
        """.trimIndent(),
        "editable" to (gradeEdit != null),
        "points" to (initialGrade?.grade ?: "-"),
        "graderIcon" to (when (initialGrade?.is_autograde) {
            null -> ""
            true -> Icons.robot
            false -> Icons.teacherFace
        }),
        "indirectGrade" to (initialGrade?.is_graded_directly == false),
        "graderPastIcon" to Icons.past,
        "gradeLabel" to Str.validGradeLabel,
        "pastGradeHelp" to Str.gradeTransferredHelp,
    )

    override fun postChildrenBuilt() {
        saveBtn.hide()
        cancelBtn.hide()
        notifyStudentCheckbox.hide()
    }

    suspend fun setGrade(grade: CourseExercisesTeacherDAO.Grade?) {
        initialGrade = grade
        createAndBuild().await()
    }

    private suspend fun saveGrade() {
        val points = pointsField?.getValue()
        if (gradeEdit != null && points != null) {
            CourseExercisesTeacherDAO.changeGrade(
                gradeEdit.courseId, gradeEdit.courseExerciseId, gradeEdit.submissionId, points,
                notifyStudentCheckbox.value == CheckboxComp.Value.CHECKED
            ).await()
            gradeEdit.onGradeSaved()
        }
    }
}
