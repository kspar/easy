package pages.course_exercises

import Auth
import DateSerializer
import PageName
import Role
import Str
import cache.BasicCourseInfo
import getContainer
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import kotlinx.serialization.Serializable
import libheaders.Materialize
import pages.EasyPage
import pages.Title
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.*
import restore
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml
import rip.kspar.ezspa.getNodelistBySelector
import rip.kspar.ezspa.toJsObj
import tmRender
import toEstonianString
import kotlin.js.Date


object CourseExercisesPage : EasyPage() {

    enum class GraderType {
        AUTO, TEACHER
    }

    enum class ExerciseStatus {
        UNSTARTED, STARTED, COMPLETED
    }

    @Serializable
    data class StudentExercises(val exercises: List<StudentExercise>)

    @Serializable
    data class StudentExercise(
        val id: String,
        val effective_title: String,
        @Serializable(with = DateSerializer::class)
        val deadline: Date?,
        val status: ExerciseStatus,
        val grade: Int?,
        val graded_by: GraderType?,
        val ordering_idx: Int
    )


    override val pageName: PageName
        get() = PageName.EXERCISES

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_EXERCISES)

    override val pathSchema = "/courses/{courseId}/exercises"

    private val courseId: String
        get() = parsePathParams()["courseId"]

    override fun clear() {
        super.clear()
        getContainer().innerHTML = tmRender(
            "tm-loading-placeholders",
            mapOf("marginTopRem" to 6, "titleWidthRem" to 30)
        )
    }

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val scrollPosition = pageStateStr.getScrollPosFromState()

        when (Auth.activeRole) {
            Role.STUDENT -> {
                buildStudentExercises(courseId)
            }
            Role.TEACHER, Role.ADMIN -> {
                getHtml().addClass("wui3")
                TeacherCourseExercisesRootComp(courseId).createAndBuild()
            }
        }.then {
            scrollPosition?.restore()
        }
    }

    override fun onPreNavigation() {
        updateStateWithScrollPos()
    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
    }

    fun link(courseId: String): String = constructPathLink(mapOf("courseId" to courseId))


    private fun buildStudentExercises(courseId: String) = doInPromise {
        val courseInfoPromise = BasicCourseInfo.get(courseId)
        val exercisesResp = fetchEms(
            "/student/courses/$courseId/exercises", ReqMethod.GET,
            successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessPage)
        ).await()

        val courseTitle = courseInfoPromise.await().title
        val exercises = exercisesResp.parseTo(StudentExercises.serializer()).await()

        Title.update { it.parentPageTitle = courseTitle }

        val exerciseArray = exercises.exercises
            .sortedBy { it.ordering_idx }
            .map { ex ->
                val exMap = mutableMapOf<String, Any>(
                    "href" to "/courses/$courseId/exercises/${ex.id}/summary",
                    "title" to ex.effective_title,
                    "deadlineLabel" to Str.deadlineLabel(),
                    "autoLabel" to Str.gradedAutomatically(),
                    "teacherLabel" to Str.gradedByTeacher(),
                    "missingLabel" to Str.notGradedYet()
                )

                ex.deadline?.let {
                    exMap["deadline"] = it.toEstonianString()
                }

                when (ex.status) {
                    ExerciseStatus.UNSTARTED -> {
                        exMap["unstarted"] = true
                    }
                    ExerciseStatus.STARTED -> {
                        if (ex.graded_by != null)
                            exMap["started"] = true
                    }
                    ExerciseStatus.COMPLETED -> {
                        exMap["completed"] = true
                    }
                }

                when (ex.graded_by) {
                    GraderType.AUTO -> {
                        exMap["evalAuto"] = true
                        exMap["points"] = ex.grade?.toString() ?: error("Grader type is set but no grade found")
                    }
                    GraderType.TEACHER -> {
                        exMap["evalTeacher"] = true
                        exMap["points"] = ex.grade?.toString() ?: error("Grader type is set but no grade found")
                    }
                    null -> {
                        if (ex.status != ExerciseStatus.UNSTARTED)
                            exMap["evalMissing"] = true
                    }
                }

                exMap.toJsObj()
            }.toTypedArray()

        val exercisesHtml = tmRender(
            "tm-stud-exercises-list", mapOf(
                "courses" to Str.myCourses(),
                "coursesHref" to "/courses",
                "title" to courseTitle,
                "exercises" to exerciseArray
            )
        )

        getContainer().innerHTML = exercisesHtml
        initTooltips()
    }

    private fun initTooltips() {
        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
    }
}
