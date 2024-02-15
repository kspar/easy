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
    var studentId: String,
    // TODO: arg should be DAO student object
    private val onStudentLoad: suspend () -> Unit,
    private val onNextStudent: suspend (currentStudentId: String) -> Unit,
    private val onPrevStudent: suspend (currentStudentId: String) -> Unit,
    parent: Component
) : Component(parent) {

    private lateinit var studentName: String

    private lateinit var prevStudentBtn: IconButtonComp
    private lateinit var nextStudentBtn: IconButtonComp
    private lateinit var allSubsBtn: ButtonComp

    override val children: List<Component>
        get() = listOf(prevStudentBtn, nextStudentBtn, allSubsBtn)

    override fun create() = doInPromise {
        studentName = "Murelin Säde"

        onStudentLoad()

        prevStudentBtn = IconButtonComp(
            Icons.previous, "Eelmine õpilane",
            onClick = { onPrevStudent(studentId) },
//            enabled = (prevStudent != null),
            parent = this
        )
        nextStudentBtn =
            IconButtonComp(
                Icons.next, "Järgmine õpilane",
                onClick = { onNextStudent(studentId) },
//                enabled = (nextStudent != null),
                parent = this
            )
        allSubsBtn =
            ButtonComp(ButtonComp.Type.TEXT, "esitus # 3", Icons.history, onClick = ::openAllSubsModal, parent = this)
    }

    override fun render() = template(
        """
            <ez-submission style='display: flex; justify-content: space-between; flex-wrap: wrap;'>
                <ez-sub-title style='display: flex; align-items: center;'>
                    <ez-sub-student-name style='font-size: 1.2em; margin-right: 1rem;'>{{name}}</ez-sub-student-name>
                    $prevStudentBtn
                    $nextStudentBtn
                </ez-sub-title>
                <ez-sub-title-secondary style='display: flex; align-items: center;'>
                    <span style='margin: 0 1rem;'>{{subTime}}</span> 
                    · 
                    $allSubsBtn
                </ez-sub-title-secondary>
            </ez-submission>
            
            
        """.trimIndent(),
        "name" to studentName,
        "timeIcon" to Icons.pending,
        "subTime" to EzDate.now().toHumanString(EzDate.Format.FULL),
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