package pages.grade_table

import HumanStringComparator
import Icons
import dao.CourseExercisesStudentDAO
import dao.CourseExercisesTeacherDAO
import kotlinx.browser.document
import kotlinx.coroutines.await
import libheaders.CSV
import nowTimestamp
import org.w3c.dom.HTMLAnchorElement
import pages.course_exercise.ExerciseSummaryPage
import pages.sidenav.Sidenav
import rip.kspar.ezspa.*
import template
import tmRender
import translation.Str

class GradeTableTableComp(
    private val courseId: String,
    private var groupId: String?,
    private var showSubNumbers: Boolean,
    private var comparator: Comparator<StudentRow>,
    parent: Component,
    dstId: String = IdGenerator.nextId()
) : Component(parent, dstId) {

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
        val id: String,
        val firstName: String,
        val lastName: String,
        val groups: List<String>,
        val finishedCount: Int,
        val grades: List<GradeCell>,
    )

    data class GradeCell(
        val courseExerciseId: String,
        val grade: Int?,
        val submissionNumber: Int?,
        val status: CourseExercisesStudentDAO.SubmissionStatus,
        val isAutograde: Boolean?,
    )


    private lateinit var table: Table

    override fun create() = doInPromise {
        val exercisesData = CourseExercisesTeacherDAO.getCourseExercises(courseId, groupId).await()

        val exercises = exercisesData.map {
            ExerciseRow(it.effectiveTitle, ExerciseSummaryPage.link(courseId, it.course_exercise_id), it.isVisibleNow)
        }

        // same sorting as in participants page
        val students = exercisesData.firstOrNull()?.latest_submissions?.sortedWith(
            compareBy<CourseExercisesTeacherDAO.LatestStudentSubmission, String?>(HumanStringComparator) {
                it.groups.getOrNull(0)?.name
            }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(1)?.name }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(2)?.name }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(3)?.name }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(4)?.name }
                .thenBy { it.family_name.lowercase() }
                .thenBy { it.given_name.lowercase() }
        )?.map { student ->
            val grades = exercisesData.map {
                val submission = it.latest_submissions.single { it.student_id == student.student_id }
                GradeCell(
                    it.course_exercise_id,
                    submission.submission?.grade?.grade,
                    submission.submission?.submission_number,
                    submission.status,
                    submission.submission?.grade?.is_autograde
                )
            }
            StudentRow(
                student.student_id,
                student.given_name,
                student.family_name,
                student.groups.map { it.name },
                grades.count { it.status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED },
                grades
            )
        }.orEmpty().sortedWith(comparator)

        table = Table(exercises, students)

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                Str.grades,
                listOf(Sidenav.Action(Icons.download, Str.exportGrades, ::downloadGradesCSV))
            )
        )
    }

    override fun renderLoading() = Icons.spinner

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
                    "num" to it.submissionNumber,
                    "teacherGraded" to (it.isAutograde == false),
                    "teacherIcon" to Icons.teacherFace,
                    "unstarted" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.UNSTARTED),
                    "ungraded" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.UNGRADED),
                    "unfinished" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.STARTED),
                    "finished" to (it.status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED),
                    "submissionHref" to ExerciseSummaryPage.link(courseId, it.courseExerciseId, student.id),
                )
            }.toTypedArray()

            objOf(
                "name" to "${student.firstName} ${student.lastName}",
                "finishedCount" to student.finishedCount,
                "studentSummaryTitle" to "${Str.solvedExercises1} ${student.finishedCount} ${Str.solvedExercises2}",
                "grades" to grades,
            )
        }.toTypedArray()

        val exerciseSummaries = table.exercises.mapIndexed { i, _ ->
            val finishedCount = table.students.count {
                it.grades[i].status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED
            }
            objOf(
                "finishedCount" to finishedCount,
                "exerciseSummaryTitle" to "${Str.solvedBy1} $finishedCount ${Str.solvedBy2}",
            )
        }.toTypedArray()

        return when {
            exercises.isEmpty() -> tmRender(
                "t-s-missing-content-wandering-eyes",
                "text" to Str.emptyGradeTablePlaceholder
            )

            else -> template(
                """
                    <div class="grades-wrap">
                        <div class="grade-table-wrap">
                            <table class="colored truncated-ex {{#showSubNums}}sub-nums{{/showSubNums}}">
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
                                            <td class="{{#finished}}finished{{/finished}} {{#unfinished}}unfinished{{/unfinished}} {{#unstarted}}unstarted{{/unstarted}} {{#ungraded}}ungraded{{/ungraded}}">
                                                <a href='{{submissionHref}}'>
                                                    <span style='margin-right: 5px; font-weight: 500;'>{{grade}}</span>
                                                    {{#showSubNums}}{{#num}}
                                                        ·
                                                        <span style='margin: 0 5px;'>#{{num}}</span>
                                                    {{/num}}{{/showSubNums}}
                                                    {{#teacherGraded}}{{{teacherIcon}}}{{/teacherGraded}}</a>
                                            </td>
                                        {{/grades}}
                                    </tr>
                                {{/students}}
                                </tbody>
                            </table>
                        </div>
                    </div>
                """.trimIndent(),
                "showSubNums" to showSubNumbers,
                "exerciseCount" to exercises.size,
                "exerciseCountTitle" to "${Str.total} ${exercises.size} ${if (exercises.size == 1) Str.exerciseSingular else Str.exercisePlural}",
                "studentCount" to students.size,
                "studentCountTitle" to "${Str.total} ${students.size} ${if (students.size == 1) Str.studentsSingular else Str.studentsPlural}",
                "exercises" to exercises,
                "students" to students,
                "exerciseSummaries" to exerciseSummaries,
            )
        }
    }

    suspend fun setShowSubNumbers(subNumbers: Boolean) {
        showSubNumbers = subNumbers
        createAndBuild().await()
    }

    suspend fun setGroup(groupId: String?) {
        this.groupId = groupId
        createAndBuild().await()
    }

    suspend fun setComparator(comparator: Comparator<StudentRow>) {
        this.comparator = comparator
        createAndBuild().await()
    }

    private fun downloadGradesCSV(sidenavAction: Sidenav.Action) {
        val fields = listOf(
            objOf("id" to "name", "label" to Str.name),
        ) + table.exercises.flatMapIndexed { i, e ->
            buildList {
                add(
                    objOf(
                        "id" to "ex$i",
                        "label" to e.title
                    )
                )
                if (showSubNumbers)
                    add(
                        objOf(
                            "id" to "ex$i-subs",
                            "label" to "${Str.submissionCount} - " + e.title
                        )
                    )
            }
        }

        val records = table.students.map {
            (listOf("name" to "${it.firstName} ${it.lastName}") + it.grades.flatMapIndexed { i, g ->
                buildList<Pair<String, Int?>> {
                    add(("ex$i" to g.grade))
                    if (showSubNumbers)
                        add("ex$i-subs" to g.submissionNumber)
                }
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
        downloadLink.setAttribute("download", "${Str.grades}-$courseId-${nowTimestamp()}.csv")
        downloadLink.click()
    }
}