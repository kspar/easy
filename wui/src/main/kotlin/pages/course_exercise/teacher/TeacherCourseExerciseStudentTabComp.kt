package pages.course_exercise.teacher

import EzDate
import Icons
import components.ButtonComp
import components.IconButtonComp
import dao.CourseExercisesTeacherDAO
import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template

class TeacherCourseExerciseStudentTabComp(
    private val courseId: String,
    private val courseExId: String,
    private val exerciseId: String,
    private val deadline: EzDate?,
    var studentId: String,
    var submissionId: String?,
    private val onStudentLoad: suspend (CourseExercisesTeacherDAO.StudentSubmissionDetails?) -> Unit,
    private val onNextStudent: suspend (currentStudentId: String) -> Unit,
    private val onPrevStudent: suspend (currentStudentId: String) -> Unit,
    parent: Component
) : Component(parent) {

    //    private lateinit var studentName: String
//    private lateinit var subTime: EzDate
    private var submission: CourseExercisesTeacherDAO.StudentSubmissionDetails? = null

    private lateinit var prevStudentBtn: IconButtonComp
    private lateinit var nextStudentBtn: IconButtonComp
    private var allSubsBtn: ButtonComp? = null

    override val children: List<Component>
        get() = listOfNotNull(prevStudentBtn, nextStudentBtn, allSubsBtn)

    override fun create() = doInPromise {
        // TODO: need to get student name from somewhere

        submission = submissionId?.let {
            CourseExercisesTeacherDAO.getSubmissionDetails(courseId, courseExId, it).await()
        }

        onStudentLoad(submission)

        prevStudentBtn = IconButtonComp(
            Icons.previous, null,
            onClick = { onPrevStudent(studentId) },
            parent = this
        )
        nextStudentBtn = IconButtonComp(
            Icons.next, null,
            onClick = { onNextStudent(studentId) },
            parent = this
        )
        allSubsBtn = submission?.let {
            ButtonComp(
                ButtonComp.Type.TEXT,
                "esitus # " + it.submission_number,
                Icons.history,
                onClick = ::openAllSubsModal, parent = this
            )
        }

    }

    override fun render() = template(
        """
            <ez-sub-header style='display: flex; justify-content: space-between; flex-wrap: wrap;'>
                <ez-sub-title style='display: flex; align-items: center;'>
                    <ez-sub-student-name style='font-size: 1.2em; margin-right: 1rem;'>{{name}}</ez-sub-student-name>
                    $prevStudentBtn
                    $nextStudentBtn
                </ez-sub-title>
                {{#hasSubmission}}
                    <ez-sub-title-secondary style='display: flex; align-items: center;'>
                        {{#overDeadline}}
                            <ez-deadline-close style='display: flex; font-weight: 500;'>
                                {{{deadlineIcon}}}
                        {{/overDeadline}}
                                <span style='margin: 0 1rem;'>{{subTime}}</span>
                        {{#overDeadline}}
                            </ez-deadline-close>                    
                        {{/overDeadline}}
                        · 
                        $allSubsBtn
                    </ez-sub-title-secondary>
                {{/hasSubmission}}
            </ez-sub-header>
            
            
        """.trimIndent(),
        "name" to "Murelin Säde",
        "hasSubmission" to (submission != null),
        "timeIcon" to Icons.pending,
        "overDeadline" to submission?.let { deadline != null && deadline < it.created_at },
        "deadlineIcon" to Icons.alarmClock,
        "subTime" to submission?.created_at?.toHumanString(EzDate.Format.FULL),
    )

    suspend fun setStudent(id: String, submissionIdd: String?) {
        studentId = id
//        studentName = name
        submissionId = submissionIdd
        createAndBuild().await()
    }

    fun setPrevNextBtns(prevStudentName: String?, nextStudentName: String?) {
        prevStudentBtn.label = prevStudentName
        prevStudentBtn.enabled = prevStudentName != null
        prevStudentBtn.rebuild()
        nextStudentBtn.label = nextStudentName
        nextStudentBtn.enabled = nextStudentName != null
        nextStudentBtn.rebuild()
    }

    private suspend fun openAllSubsModal() {

    }
}