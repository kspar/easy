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

class GradeTableTableComp(
    private val courseId: String,
    private var groupId: String?,
    private var showSubNumbers: Boolean,
    private var truncateExerciseTitles: Boolean,
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
        val name: String,
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
            StudentRow(
                student.student_id,
                student.name,
                exercisesData.map {
                    val submission = it.latest_submissions.single { it.student_id == student.student_id }
                    GradeCell(
                        it.course_exercise_id,
                        submission.submission?.grade?.grade,
                        submission.submission?.submission_number,
                        submission.status,
                        submission.submission?.grade?.is_autograde
                    )
                }
            )
        }.orEmpty()

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

            val finishedCount = student.grades.count {
                it.status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED
            }

            objOf(
                "name" to student.name,
                "finishedCount" to finishedCount,
                "studentSummaryTitle" to "Lahendanud $finishedCount ülesannet",
                "grades" to grades,
            )
        }.toTypedArray()

        val exerciseSummaries = table.exercises.mapIndexed { i, _ ->
            val finishedCount = table.students.count {
                it.grades[i].status == CourseExercisesStudentDAO.SubmissionStatus.COMPLETED
            }
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
                            <table class="colored {{#truncateTitles}}truncated-ex{{/truncateTitles}} {{#showSubNums}}sub-nums{{/showSubNums}}">
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
                "truncateTitles" to truncateExerciseTitles,
                "showSubNums" to showSubNumbers,
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

    suspend fun setSettings(subNumbers: Boolean? = null, truncatedTitles: Boolean? = null) {
        subNumbers?.let {
            showSubNumbers = subNumbers
        }
        truncatedTitles?.let {
            truncateExerciseTitles = truncatedTitles
        }
        createAndBuild().await()
    }

    suspend fun setGroup(groupId: String?) {
        this.groupId = groupId
        createAndBuild().await()
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