package pages.grade_table

import AppProperties
import CONTENT_CONTAINER_ID
import PageName
import PaginationConf
import Role
import Str
import components.BreadcrumbsComp
import components.Crumb
import getContainer
import getLastPageOffset
import isNotNullAndTrue
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.Materialize
import objOf
import org.w3c.dom.HTMLSelectElement
import pages.EasyPage
import pages.leftbar.Leftbar
import plainDstStr
import queries.*
import rip.kspar.ezspa.*
import tmRender
import kotlin.js.Promise
import kotlin.math.min

object GradeTablePage : EasyPage() {

    private const val PAGE_STEP = AppProperties.GRADE_TABLE_ROWS_ON_PAGE

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

    override val pageName: Any
        get() = PageName.GRADE_TABLE

    override val leftbarConf: Leftbar.Conf
        get() = Leftbar.Conf(extractSanitizedCourseId())

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/grades/?$")

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        val courseId = extractSanitizedCourseId()
        buildTable(courseId, 0, PAGE_STEP)
    }

    private fun buildTable(courseId: String, offset: Int, limit: Int) {
        MainScope().launch {
            val q = createQueryString("offset" to offset.toString(), "limit" to limit.toString())
            val gradesPromise = fetchEms("/courses/teacher/$courseId/grades$q", ReqMethod.GET,
                    successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessPage)
            val courseTitle = BasicCourseInfo.get(courseId).await().title

            val gradeTable = gradesPromise.await()
                    .parseTo(GradeTable.serializer()).await()

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


            val paginationConf = if (gradeTable.student_count > PAGE_STEP) {
                PaginationConf(offset + 1, min(offset + limit, gradeTable.student_count), gradeTable.student_count,
                        offset != 0, offset + limit < gradeTable.student_count)
            } else null

            getContainer().innerHTML = tmRender("tm-teach-gradetable", mapOf(
                    "myCoursesLabel" to Str.myCourses(),
                    "courseHref" to "/courses/$courseId/exercises",
                    "title" to courseTitle,
                    "gradesLabel" to Str.gradesLabel(),
                    "exercises" to exercises,
                    "students" to students,
                    "hasPagination" to (paginationConf != null),
                    "pageStart" to paginationConf?.pageStart,
                    "pageEnd" to paginationConf?.pageEnd,
                    "pageTotal" to paginationConf?.pageTotal,
                    "pageTotalLabel" to ", kokku ",
                    "canGoBack" to paginationConf?.canGoBack,
                    "canGoForward" to paginationConf?.canGoForward
            ))

            if (paginationConf?.canGoBack.isNotNullAndTrue) {
                getElemsByClass("go-first").onVanillaClick(true) {
                    buildTable(courseId, 0, PAGE_STEP)
                }
                getElemsByClass("go-back").onVanillaClick(true) {
                    buildTable(courseId, offset - PAGE_STEP, PAGE_STEP)
                }
            }

            if (paginationConf?.canGoForward.isNotNullAndTrue) {
                getElemsByClass("go-forward").onVanillaClick(true) {
                    buildTable(courseId, offset + PAGE_STEP, PAGE_STEP)
                }
                getElemsByClass("go-last").onVanillaClick(true) {
                    buildTable(courseId, getLastPageOffset(gradeTable.student_count, PAGE_STEP), PAGE_STEP)
                }
            }
        }
    }
}

private fun extractSanitizedCourseId(): String {
    val path = window.location.pathname
    val match = path.match("^/courses/(\\w+)/grades/?$")
    if (match != null && match.size == 2) {
        return match[1]
    } else {
        error("Unexpected match on path: ${match?.joinToString()}")
    }
}


object NewGradeTablePage : EasyPage() {
    private const val PAGE_STEP = AppProperties.GRADE_TABLE_ROWS_ON_PAGE

    override val pageName: Any
        get() = PageName.GRADE_TABLE

    override val leftbarConf: Leftbar.Conf
        get() = Leftbar.Conf(extractSanitizedCourseId())

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/grades/?$")


    private var rootComp: GradeTableRootComponent? = null

    override fun build(pageStateStr: String?) {
        doInPromise {
            super.build(pageStateStr)
            val courseId = extractSanitizedCourseId()
            val root = GradeTableRootComponent(courseId, CONTENT_CONTAINER_ID)
            root.createAndBuild().await()
        }
    }
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
        cardComp = GradeTableCardComp(courseTitle, this)
    }

    override fun render() = plainDstStr(crumbsComp.dstId, cardComp.dstId)
}

class GradeTableCardComp(
        private val courseTitle: String,
        parent: Component?
) : Component(parent) {

    private lateinit var groupSelectComp: SelectComp
    private lateinit var tableComp: GradeTableTableComp
    // Static ID for table since it's recreated
    private val tableDstId = IdGenerator.nextId()

    override val children: List<Component>
        get() = listOf(groupSelectComp, tableComp)

    override fun create() = doInPromise {
        // TODO: get groups
        groupSelectComp = SelectComp("Grupp",
                listOf(SelectComp.Option("Esimene", "1"), SelectComp.Option("Teine", "2", true)),
                ::handleGroupChange, this)
        tableComp = GradeTableTableComp(null, this, tableDstId)
    }

    override fun render() = tmRender("t-c-grades-card",
            "title" to courseTitle,
            "groupSelectDstId" to groupSelectComp.dstId,
            "tableDstId" to tableDstId)

    private fun handleGroupChange(newGroupId: String?) {
        doInPromise {
            tableComp = GradeTableTableComp(newGroupId, this, tableDstId)
            tableComp.createAndBuild().await()
        }
    }
}

class SelectComp(
        private val label: String,
        private val options: List<Option>,
        private val onOptionChange: ((String) -> Unit)? = null,
        parent: Component
) : Component(parent) {

    data class Option(val label: String, val value: String, val preselected: Boolean = false)

    private val selectId = IdGenerator.nextId()

    override fun render() = tmRender("t-c-select",
            "selectId" to selectId,
            "selectLabel" to label,
            "options" to options.map { mapOf("value" to it.value, "isSelected" to it.preselected, "label" to it.label) }
    )

    override fun postRender() {
        Materialize.FormSelect.init(getElemById(selectId), objOf(
                "dropdownOptions" to objOf(
                        "coverTrigger" to false,
                        "autoFocus" to false
                )
        ))
        val selectElement = getElemByIdAs<HTMLSelectElement>(selectId)
        selectElement.onChange {
            onOptionChange?.invoke(selectElement.value)
        }
    }
}

class GradeTableTableComp(
        private val groupId: String?,
        parent: Component,
        dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

    override fun create() = doInPromise {
        // TODO: get grades
        sleep(3000).await()
    }

    override fun renderLoading(): String {
        return "loading..."
    }

    override fun render(): String {
        return "table comp for group $groupId"
    }

}
