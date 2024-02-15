package pages.course_exercise.teacher

import EzDate
import Icons
import components.ButtonComp
import components.IconButtonComp
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
    // TODO: arg should be DAO student object
    private val onStudentLoad: suspend () -> Unit,
    private val onNextStudent: suspend (currentStudentId: String) -> Unit,
    private val onPrevStudent: suspend (currentStudentId: String) -> Unit,
    parent: Component
) : Component(parent) {

    private lateinit var studentName: String
    private lateinit var subTime: EzDate

    private lateinit var prevStudentBtn: IconButtonComp
    private lateinit var nextStudentBtn: IconButtonComp
    private lateinit var allSubsBtn: ButtonComp

    override val children: List<Component>
        get() = listOf(prevStudentBtn, nextStudentBtn, allSubsBtn)

    override fun create() = doInPromise {
        studentName = "Murelin Säde"
        subTime = EzDate.now()

        onStudentLoad()

        prevStudentBtn = IconButtonComp(
            Icons.previous, null,
            onClick = { onPrevStudent(studentId) },
            parent = this
        )
        nextStudentBtn =
            IconButtonComp(
                Icons.next, null,
                onClick = { onNextStudent(studentId) },
                parent = this
            )
        allSubsBtn =
            ButtonComp(ButtonComp.Type.TEXT, "esitus # 3", Icons.history, onClick = ::openAllSubsModal, parent = this)
    }

    override fun render() = template(
        """
            <ez-sub-header style='display: flex; justify-content: space-between; flex-wrap: wrap;'>
                <ez-sub-title style='display: flex; align-items: center;'>
                    <ez-sub-student-name style='font-size: 1.2em; margin-right: 1rem;'>{{name}}</ez-sub-student-name>
                    $prevStudentBtn
                    $nextStudentBtn
                </ez-sub-title>
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
            </ez-sub-header>
            
            
        """.trimIndent(),
        "name" to studentName,
        "timeIcon" to Icons.pending,
        "overDeadline" to (deadline != null && deadline < subTime),
        "deadlineIcon" to Icons.alarmClock,
        "subTime" to subTime.toHumanString(EzDate.Format.FULL),
    )

    suspend fun setStudent(id: String, name: String) {
        studentId = id
        studentName = name
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