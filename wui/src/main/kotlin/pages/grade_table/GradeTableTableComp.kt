package pages.grade_table

import Icons
import dao.ExerciseDAO
import debug
import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import libheaders.CSV
import nowTimestamp
import org.w3c.dom.HTMLAnchorElement
import pages.course_exercise.ExerciseSummaryPage
import pages.sidenav.Sidenav
import queries.*
import rip.kspar.ezspa.*
import template
import tmRender

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
        val grader_type: ExerciseDAO.GraderType? = null,
        val feedback: String? = null
    )

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
        val grader: ExerciseDAO.GraderType?,
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

        data class GradeWithInfo(val grade: Int?, val status: ExerciseStatus, val grader: ExerciseDAO.GraderType?)

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
                    "teacherGraded" to (it.grader == ExerciseDAO.GraderType.TEACHER),
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

            else -> template(
                """
                    <div class="grades-wrap">
                        <div class="grade-table-wrap">
                            <table class="colored truncated-ex">
                                <thead><tr>
                                    <th class="header-spacer"></th>
                                    <th><span class="text" title="{{exerciseCountTitle}}">Σ ({{exerciseCount}})</span></th>
                                    {{#exercises}}
                                        <th><a class="wrap uncolored {{#invisible}}invisible{{/invisible}}" href="{{href}}"><span class="text" title="{{title}}">{{title}}</span></a></th>
                                    {{/exercises}}
                                </tr></thead>
                                <tbody>
                                <tr>
                                    <td class="sticky-col neutral" title="{{studentCountTitle}}">Σ ({{studentCount}})</td>
                                    <td class="neutral"></td>
                                    {{#exerciseSummaries}}
                                        <td class="neutral" title="{{exerciseSummaryTitle}}">{{finishedCount}}</td>
                                    {{/exerciseSummaries}}
                                </tr>
                                {{#students}}
                                    <tr>
                                        <td class="sticky-col neutral truncate" title="{{name}}">{{name}}</td>
                                        <td class="neutral" title="{{studentSummaryTitle}}">{{finishedCount}}</td>
                                        {{#grades}}
                                            <td class="{{#finished}}finished{{/finished}} {{#unfinished}}unfinished{{/unfinished}} {{#unstarted}}unstarted{{/unstarted}} {{#ungraded}}ungraded{{/ungraded}}">{{grade}}{{#teacherGraded}}*{{/teacherGraded}}</td>
                                        {{/grades}}
                                    </tr>
                                {{/students}}
                                </tbody>
                            </table>
                        </div>
                    </div>
                """.trimIndent(),
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