package pages.grade_table

import CONTENT_CONTAINER_ID
import Icons
import PageName
import Role
import Str
import cache.BasicCourseInfo
import components.BreadcrumbsComp
import components.Crumb
import components.form.SelectComp
import debug
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import libheaders.CSV
import nowTimestamp
import org.w3c.dom.HTMLAnchorElement
import pages.EasyPage
import pages.course_exercise.ExerciseSummaryPage
import pages.Title
import pages.sidenav.ActivePage
import pages.sidenav.Sidenav
import queries.*
import rip.kspar.ezspa.*
import tmRender
import kotlin.js.Promise


object GradeTablePage : EasyPage() {

    override val pageName: Any
        get() = PageName.GRADE_TABLE

    override val sidenavSpec: Sidenav.Spec
        get() = Sidenav.Spec(courseId, ActivePage.COURSE_GRADES)

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override val pathSchema = "/courses/{courseId}/grades"

    override val courseId: String
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

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
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
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        crumbsComp = BreadcrumbsComp(
            listOf(
                Crumb.myCourses,
                Crumb(courseTitle, "/courses/$courseId/exercises"),
                Crumb(Str.gradesLabel())
            ), this
        )
        cardComp = GradeTableCardComp(courseId, courseTitle, this)

        Title.update {
            it.pageTitle = Str.gradesLabel()
            it.parentPageTitle = courseTitle
        }
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
        val groups = fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET, successChecker = { http200 },
            errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await()
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
            groupSelectComp = SelectComp("Rühm", options, onOptionChange = ::handleGroupChange, parent = this)
        }

        tableComp = GradeTableTableComp(
            courseId,
            groupId = if (groups.size == 1) groups[0].id else null,
            parent = this,
            dstId = tableDstId
        )
    }

    override fun render() = tmRender(
        "t-c-grades-card",
        "title" to courseTitle,
        "groupSelectDstId" to groupSelectComp?.dstId,
        "tableDstId" to tableDstId
    )

    private fun handleGroupChange(newGroupId: String?) {
        doInPromise {
            tableComp = GradeTableTableComp(
                courseId,
                groupId = newGroupId,
                parent = this,
                dstId = tableDstId
            )
            tableComp.createAndBuild().await()
        }
    }
}

class GradeTableTableComp(
    private val courseId: String,
    private val groupId: String?,
    parent: Component,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

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

    data class Table(
        val exercises: List<ExerciseRow>,
        val students: List<StudentRow>,
    )

    data class ExerciseRow(
        val title: String,
        val href: String,
        val isVisible: Boolean,
    )

    data class StudentRow(
        val name: String,
        val grades: List<GradeCell>,
    )

    data class GradeCell(
        val grade: Int?,
        val status: ExerciseStatus,
        val grader: GraderType?,
    )


    private lateinit var table: Table

    override fun create() = doInPromise {
        debug { "Building grade table for course $courseId, group $groupId" }
        val q = createQueryString("group" to groupId)
        val gradesPromise = fetchEms(
            "/courses/teacher/$courseId/grades$q", ReqMethod.GET,
            successChecker = { http200 }, errorHandler = ErrorHandlers.noCourseAccessMsg
        )

        val gradeTable = gradesPromise.await()
            .parseTo(GradeTable.serializer()).await()

        val exercises = gradeTable.exercises.map {
            ExerciseRow(it.effective_title, ExerciseSummaryPage.link(courseId, it.exercise_id), it.student_visible)
        }

        val allStudents = gradeTable.students.map { it.student_id }.toSet()

        data class GradeWithInfo(val grade: Int?, val status: ExerciseStatus, val grader: GraderType?)

        val gradesMap = mutableMapOf<String, MutableList<GradeWithInfo>>()
        gradeTable.exercises.forEach { ex ->
            ex.grades.forEach {
                val gradeInfo = when {
                    it.grade == null -> GradeWithInfo(null, ExerciseStatus.UNGRADED, it.grader_type)
                    it.grade >= ex.grade_threshold -> GradeWithInfo(it.grade, ExerciseStatus.FINISHED, it.grader_type)
                    else -> GradeWithInfo(it.grade, ExerciseStatus.UNFINISHED, it.grader_type)
                }
                gradesMap.getOrPut(it.student_id) { mutableListOf() }
                    .add(gradeInfo)
            }
            // Add "grade missing" grades
            val missingStudents = allStudents - ex.grades.map { it.student_id }.toSet()
            missingStudents.forEach {
                gradesMap.getOrPut(it) { mutableListOf() }.add(
                    GradeWithInfo(null, ExerciseStatus.UNSTARTED, null)
                )
            }
        }

        val students = gradeTable.students.sortedBy { it.family_name }.map { student ->
            val grades = gradesMap[student.student_id]?.map {
                GradeCell(it.grade, it.status, it.grader)
            } ?: emptyList()

            StudentRow("${student.given_name} ${student.family_name}", grades)
        }

        table = Table(exercises, students)


        Sidenav.replacePageSection(
            Sidenav.PageSection(
                "Hinded",
                listOf(Sidenav.Action(Icons.download, "Ekspordi hindetabel", ::downloadGradesCSV))
            )
        )
    }

    override fun renderLoading(): String = "Laen hindeid..."

    override fun render(): String {
        val exercises = table.exercises.map {
            objOf(
                "title" to it.title,
                "href" to it.href,
                "invisible" to !it.isVisible,
            )
        }.toTypedArray()

        val students = table.students.map { student ->
            val grades = student.grades.map {
                objOf(
                    "grade" to (it.grade?.toString() ?: "-"),
                    "teacherGraded" to (it.grader == GraderType.TEACHER),
                    "unstarted" to (it.status == ExerciseStatus.UNSTARTED),
                    "ungraded" to (it.status == ExerciseStatus.UNGRADED),
                    "unfinished" to (it.status == ExerciseStatus.UNFINISHED),
                    "finished" to (it.status == ExerciseStatus.FINISHED)
                )
            }.toTypedArray()

            val finishedCount = student.grades.count { it.status == ExerciseStatus.FINISHED }

            objOf(
                "name" to student.name,
                "finishedCount" to finishedCount,
                "studentSummaryTitle" to "Lahendanud $finishedCount ülesannet",
                "grades" to grades,
            )
        }.toTypedArray()

        val exerciseSummaries = table.exercises.mapIndexed { i, _ ->
            val finishedCount = table.students.count { it.grades[i].status == ExerciseStatus.FINISHED }
            objOf(
                "finishedCount" to finishedCount,
                "exerciseSummaryTitle" to "Lahendatud $finishedCount õpilase poolt",
            )
        }.toTypedArray()

        return when {
            exercises.isEmpty() -> tmRender(
                "t-s-missing-content-wandering-eyes",
                "text" to "Kui sel kursusel oleks mõni ülesanne, siis näeksid siin hindetabelit :-)"
            )

            else -> tmRender(
                "t-c-grades-table",
                "exerciseCount" to exercises.size,
                "exerciseCountTitle" to "Kokku ${exercises.size} ülesannet",
                "studentCount" to students.size,
                "studentCountTitle" to "Kokku ${students.size} õpilast",
                "exercises" to exercises,
                "students" to students,
                "exerciseSummaries" to exerciseSummaries,
            )
        }
    }

    private fun downloadGradesCSV(sidenavAction: Sidenav.Action) {
        val fields = listOf(
            objOf("id" to "name", "label" to "Nimi"),
        ) + table.exercises.mapIndexed { i, e ->
            objOf(
                "id" to "ex$i",
                "label" to e.title
            )
        }

        val records = table.students.map {
            (listOf("name" to it.name) + it.grades.mapIndexed { i, g ->
                "ex$i" to g.grade
            }).toMap().toJsObj()
        }

        val csv = CSV.serialize(
            objOf(
                "fields" to fields.toTypedArray(),
                "records" to records.toTypedArray(),
            ), objOf("delimiter" to ";")
        )

        val downloadLink = document.createElement("a") as HTMLAnchorElement
        downloadLink.setAttribute("href", "data:text/plain;charset=utf-8,${csv.encodeURIComponent()}")
        downloadLink.setAttribute("download", "hinded-$courseId-${nowTimestamp()}.csv")
        downloadLink.click()
    }
}
