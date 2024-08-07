package pages.course_exercise.teacher

import Icons
import Key
import LocalStore
import components.CheckboxComp
import components.IconButtonComp
import components.form.IntFieldComp
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

    private var pointsField: IntFieldComp? = null
    private lateinit var saveBtn: IconButtonComp
    private lateinit var cancelBtn: IconButtonComp
    private lateinit var notifyStudentCheckbox: CheckboxComp

    override val children: List<Component>
        get() = listOfNotNull(pointsField, saveBtn, cancelBtn, notifyStudentCheckbox)

    override fun create() = doInPromise {
        if (gradeEdit != null)
            pointsField = IntFieldComp(
                "", true, 0, 100, fieldNameForMessage = Str.gradeFieldLabel,
                initialValue = initialGrade?.grade,
                paintRequiredOnInput = false,
                onValueChange = {
                    saveBtn.show()
                    cancelBtn.show()
                    notifyStudentCheckbox.show()
                },
                onValidChange = {
                    saveBtn.setEnabled(it)
                },
                onENTER = { saveGrade() },
                parent = this
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
            <ez-flex>
                {{gradeLabel}}:
                {{#editable}}<ez-inline-flex style=''>$pointsField</ez-inline-flex>{{/editable}} 
                {{^editable}}{{points}}{{/editable}}
                / 100
                <ez-flex style='margin-left: 1rem; align-items: start;' {{#indirectGrade}}title='{{pastGradeHelp}}'{{/indirectGrade}}>
                    <ez-flex class='icon-med'>{{{graderIcon}}}</ez-flex>
                    {{#indirectGrade}}<ez-flex class='icon-small'>{{{graderPastIcon}}}</ez-flex>{{/indirectGrade}}
                </ez-flex>
                $saveBtn
                $cancelBtn
                $notifyStudentCheckbox
            </ez-flex>
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
        val points = pointsField?.getIntValue()
        if (gradeEdit != null && points != null) {
            CourseExercisesTeacherDAO.changeGrade(
                gradeEdit.courseId, gradeEdit.courseExerciseId, gradeEdit.submissionId, points,
                notifyStudentCheckbox.value == CheckboxComp.Value.CHECKED
            ).await()
            gradeEdit.onGradeSaved()
        }
    }
}
