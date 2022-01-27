package pages.grade_table

import AppProperties
import CONTENT_CONTAINER_ID
import PageName
import PaginationConf
import Role
import Str
import cache.BasicCourseInfo
import components.BreadcrumbsComp
import components.Crumb
import components.form.SelectComp
import debug
import emptyToNull
import getLastPageOffset
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import objOf
import pages.EasyPage
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import plainDstStr
import queries.*
import rip.kspar.ezspa.*
import tmRender
import kotlin.js.Promise
import kotlin.math.min


object GradeTablePage : EasyPage() {
    override val pageName: Any
        get() = PageName.GRADE_TABLE

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_GRADES)

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override val pathSchema = "/courses/{courseId}/grades"

    private val courseId: String
        get() = parsePathParams()["courseId"]

    private var rootComp: GradeTableRootComponent? = null

    override fun build(pageStateStr: String?) {
        doInPromise {
            super.build(pageStateStr)
            val root = GradeTableRootComponent(courseId, CONTENT_CONTAINER_ID)
            rootComp = root
            root.createAndBuild().await()
        }
    }

    fun link(courseId: String) = constructPathLink(mapOf("courseId" to courseId))
}

class GradeTableRootComponent(
        private val courseId: String,
        dstId: String
) : Component(null, dstId) {

    private lateinit var crumbsComp: BreadcrumbsComp
    private lateinit var cardComp: GradeTableCardComp

    override val children: List<Component>
        get() = listOf(crumbsComp, cardComp)

    override fun create(): Promise<*> = doInPromise {
        val courseTitle = BasicCourseInfo.get(courseId).await().title
        crumbsComp = BreadcrumbsComp(listOf(Crumb.myCourses, Crumb(courseTitle, "/courses/$courseId/exercises"), Crumb(Str.gradesLabel())), this)
        cardComp = GradeTableCardComp(courseId, courseTitle, this)
    }

    override fun render() = plainDstStr(crumbsComp.dstId, cardComp.dstId)
}


class GradeTableCardComp(
        private val courseId: String,
        private val courseTitle: String,
        parent: Component?
) : Component(parent) {

    @Serializable
    data class Groups(
            val groups: List<Group>,
            val self_is_restricted: Boolean,
    )

    @Serializable
    data class Group(
            val id: String,
            val name: String
    )

    private var groupSelectComp: SelectComp? = null
    private lateinit var tableComp: GradeTableTableComp

    // Static ID for table since it's recreated
    private val tableDstId = IdGenerator.nextId()

    override val children: List<Component>
        get() = listOfNotNull(groupSelectComp, tableComp)

    override fun create() = doInPromise {
        val groups = fetchEms("/courses/$courseId/groups", ReqMethod.GET, successChecker = { http200 },
                errorHandler = ErrorHandlers.noCourseAccessPage).await()
                .parseTo(Groups.serializer()).await()
                .groups.sortedBy { it.name }

        if (groups.isNotEmpty()) {
            val options = mutableListOf(SelectComp.Option("Kõik õpilased", ""))
            if (groups.size == 1) {
                val group = groups[0]
                options.add(SelectComp.Option(group.name, group.id, true))
            } else {
                options.addAll(groups.map { SelectComp.Option(it.name, it.id) })
            }
            groupSelectComp = SelectComp("Rühm", options, ::handleGroupChange, this)
        }

        tableComp = GradeTableTableComp(courseId,
                groupId = if (groups.size == 1) groups[0].id else null,
                parent = this,
                dstId = tableDstId)
    }

    override fun render() = tmRender("t-c-grades-card",
            "title" to courseTitle,
            "groupSelectDstId" to groupSelectComp?.dstId,
            "tableDstId" to tableDstId)

    private fun handleGroupChange(newGroupId: String) {
        doInPromise {
            tableComp = GradeTableTableComp(courseId,
                    groupId = newGroupId.emptyToNull(),
                    parent = this,
                    dstId = tableDstId)
            tableComp.createAndBuild().await()
        }
    }
}

class GradeTableTableComp(
        private val courseId: String,
        private val groupId: String?,
        private var offsetLimit: OffsetLimit = OffsetLimit(0, AppProperties.GRADE_TABLE_ROWS_ON_PAGE),
        parent: Component,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    data class OffsetLimit(val offset: Int, val limit: Int)

    @Serializable
    data class GradeTable(
            val student_count: Int,
            val students: List<Student>,
            val exercises: List<Exercise>
    )

    @Serializable
    data class Student(
            val student_id: String,
            val given_name: String,
            val family_name: String,
            val email: String
    )

    @Serializable
    data class Exercise(
            val exercise_id: String,
            val effective_title: String,
            val grade_threshold: Int,
            val student_visible: Boolean,
            val grades: List<Grade>
    )

    @Serializable
    data class Grade(
            val student_id: String,
            val grade: Int? = null,
            val grader_type: GraderType? = null,
            val feedback: String? = null
    )

    enum class GraderType {
        AUTO, TEACHER
    }

    enum class ExerciseStatus {
        UNSTARTED, UNGRADED, UNFINISHED, FINISHED
    }


    private val pageStep = AppProperties.GRADE_TABLE_ROWS_ON_PAGE
    private lateinit var gradeTable: GradeTable
    private var paginationConf: PaginationConf? = null

    override fun create() = doInPromise {
        debug { "Building grade table for course $courseId, group $groupId, offsetlimit: $offsetLimit" }
        val (offset, limit) = offsetLimit
        val q = createQueryString("group" to groupId, "offset" to offset.toString(), "limit" to limit.toString())
        val gradesPromise = fetchEms("/courses/teacher/$courseId/grades$q", ReqMethod.GET,
                successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage)

        gradeTable = gradesPromise.await()
                .parseTo(GradeTable.serializer()).await()

        paginationConf = if (gradeTable.student_count > pageStep) {
            PaginationConf(offset + 1, min(offset + limit, gradeTable.student_count), gradeTable.student_count,
                    offset != 0, offset + limit < gradeTable.student_count)
        } else null
    }

    override fun renderLoading(): String = "Laen hindeid..."

    override fun render(): String {
        val exercises = gradeTable.exercises.map {
            objOf("exerciseTitle" to it.effective_title)
        }.toTypedArray()

        val allStudents = gradeTable.students.map { it.student_id }

        data class GradeWithInfo(val grade: String, val status: ExerciseStatus)

        val gradesMap = mutableMapOf<String, MutableList<GradeWithInfo>>()
        gradeTable.exercises.forEach { ex ->
            ex.grades.forEach {
                val gradeInfo = when {
                    it.grade == null -> GradeWithInfo("-", ExerciseStatus.UNGRADED)
                    it.grade >= ex.grade_threshold -> GradeWithInfo(it.grade.toString(), ExerciseStatus.FINISHED)
                    else -> GradeWithInfo(it.grade.toString(), ExerciseStatus.UNFINISHED)
                }
                gradesMap.getOrPut(it.student_id) { mutableListOf() }
                        .add(gradeInfo)
            }
            // Add "grade missing" grades
            val missingStudents = allStudents - ex.grades.map { it.student_id }
            missingStudents.forEach {
                gradesMap.getOrPut(it) { mutableListOf() }
                        .add(GradeWithInfo("-", ExerciseStatus.UNSTARTED))
            }
        }

        val students = gradeTable.students.sortedBy { it.family_name }.map { student ->
            val grades = gradesMap.getValue(student.student_id).map {
                objOf(
                        "grade" to it.grade,
                        "unstarted" to (it.status == ExerciseStatus.UNSTARTED),
                        "ungraded" to (it.status == ExerciseStatus.UNGRADED),
                        "unfinished" to (it.status == ExerciseStatus.UNFINISHED),
                        "finished" to (it.status == ExerciseStatus.FINISHED)
                )
            }.toTypedArray()

            objOf(
                    "name" to "${student.given_name} ${student.family_name}",
                    "grades" to grades
            )
        }.toTypedArray()

        return tmRender("t-c-grades-table",
                "exercises" to exercises,
                "students" to students,
                "hasPagination" to (paginationConf != null),
                "pageStart" to paginationConf?.pageStart,
                "pageEnd" to paginationConf?.pageEnd,
                "pageTotal" to paginationConf?.pageTotal,
                "pageTotalLabel" to ", kokku ",
                "canGoBack" to paginationConf?.canGoBack,
                "canGoForward" to paginationConf?.canGoForward
        )
    }

    override fun postRender() {
        if (paginationConf?.canGoBack == true) {
            getElemsByClass("go-first").onVanillaClick(true) {
                offsetLimit = OffsetLimit(0, pageStep)
                createAndBuild().await()
            }
            getElemsByClass("go-back").onVanillaClick(true) {
                offsetLimit = OffsetLimit(offsetLimit.offset - pageStep, pageStep)
                createAndBuild().await()
            }
        }

        if (paginationConf?.canGoForward == true) {
            getElemsByClass("go-forward").onVanillaClick(true) {
                offsetLimit = OffsetLimit(offsetLimit.offset + pageStep, pageStep)
                createAndBuild().await()
            }
            getElemsByClass("go-last").onVanillaClick(true) {
                offsetLimit = OffsetLimit(getLastPageOffset(gradeTable.student_count, pageStep), pageStep)
                createAndBuild().await()
            }
        }
    }
}
