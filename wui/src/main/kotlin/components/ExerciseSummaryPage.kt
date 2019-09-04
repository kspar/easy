package components

import Auth
import DateSerializer
import PageName
import ReqMethod
import Role
import Str
import debug
import debugFunStart
import errorMessage
import fetchEms
import getContainer
import getElemById
import getElemsByClass
import getNodelistBySelector
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.Materialize
import objOf
import onVanillaClick
import org.w3c.dom.Element
import org.w3c.dom.asList
import parseTo
import queries.BasicCourseInfo
import tmRender
import toEstonianString
import toJsObj
import kotlin.browser.window
import kotlin.js.Date

object ExerciseSummaryPage : EasyPage() {

    @Serializable
    data class TeacherExercise(
            val title: String,
            val title_alias: String?,
            val instructions_html: String?,
            val text_html: String?,
            @Serializable(with = DateSerializer::class)
            val hard_deadline: Date?,
            @Serializable(with = DateSerializer::class)
            val soft_deadline: Date?,
            val grader_type: GraderType,
            val threshold: Int,
            @Serializable(with = DateSerializer::class)
            val last_modified: Date,
            val student_visible: Boolean,
            val assessments_student_visible: Boolean,
            val grading_script: String?,
            val container_image: String?,
            val max_time_sec: Int?,
            val max_mem_mb: Int?,
            val assets: List<AutoAsset>?,
            val executors: List<AutoExecutor>?
    )

    @Serializable
    data class AutoAsset(
            val file_name: String,
            val file_content: String
    )

    @Serializable
    data class AutoExecutor(
            val executor_id: String
    )

    enum class GraderType {
        AUTO, TEACHER
    }

    @Serializable
    data class TeacherStudents(
            val student_count: Int,
            val students: List<TeacherStudent>
    )

    @Serializable
    data class TeacherStudent(
            val student_id: String,
            val given_name: String,
            val family_name: String,
            @Serializable(with = DateSerializer::class)
            val submission_time: Date?,
            val grade: Int?,
            val graded_by: GraderType?
    )

    @Serializable
    data class TeacherSubmission(
            val id: String,
            val solution: String,
            @Serializable(with = DateSerializer::class)
            val created_at: Date,
            val grade_auto: Int?,
            val feedback_auto: String?,
            val grade_teacher: Int?,
            val feedback_teacher: String?
    )


    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises/\\w+/summary/?$")

    override fun build(pageStateStr: String?) {
        val pathIds = extractSanitizedPathIds(window.location.pathname)
        val courseId = pathIds.courseId
        val courseExerciseId = pathIds.exerciseId

        when (Auth.activeRole) {
            Role.STUDENT -> buildStudentExercise(courseId)
            Role.TEACHER, Role.ADMIN -> buildTeacherExercise(courseId, courseExerciseId)
        }
    }

    private fun buildTeacherExercise(courseId: String, courseExerciseId: String) = MainScope().launch {
        val fl = debugFunStart("buildTeacherExercise")

        getContainer().innerHTML = tmRender("tm-teach-exercise", mapOf(
                "exerciseLabel" to "Ülesanne",
                "testingLabel" to "Katsetamine",
                "studentSubmLabel" to "Esitused"
        ))

        Materialize.Tabs.init(getElemById("tabs"))

        // Could be optimised to load exercise details & students in parallel,
        // requires passing an exercisePromise to buildStudents since the threshold is needed for painting
        val exerciseDetails = buildTeacherSummaryAndCrumbs(courseId, courseExerciseId)
        buildTeacherTesting()
        buildTeacherStudents(courseId, courseExerciseId, exerciseDetails.threshold)

        Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
        fl?.end()
    }

    private suspend fun buildTeacherSummaryAndCrumbs(courseId: String, courseExerciseId: String): TeacherExercise {
        val fl = debugFunStart("buildTeacherSummaryAndCrumbs")

        val exercisePromise = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET)

        val courseTitle = BasicCourseInfo.get(courseId).await().title

        val exerciseResp = exercisePromise.await()
        if (!exerciseResp.http200) {
            errorMessage { Str.somethingWentWrong() }
            error("Fetching exercises failed with status ${exerciseResp.status}")
        }

        val exercise = exerciseResp.parseTo(TeacherExercise.serializer()).await()

        getElemById("crumbs").innerHTML = tmRender("tm-teach-exercise-crumbs", mapOf(
                "coursesLabel" to "Minu kursused",
                "coursesHref" to "/courses",
                "courseTitle" to courseTitle,
                "courseHref" to "/courses/$courseId/exercises",
                "exerciseTitle" to (exercise.title_alias ?: exercise.title)
        ))

        getElemById("exercise").innerHTML = tmRender("tm-teach-exercise-summary", mapOf(
                "softDeadline" to exercise.soft_deadline?.toEstonianString(),
                "hardDeadline" to exercise.hard_deadline?.toEstonianString(),
                "graderType" to if (exercise.grader_type == GraderType.AUTO) "automaatne" else "käsitsi",
                "threshold" to exercise.threshold,
                "studentVisible" to Str.translateBoolean(exercise.student_visible),
                "assStudentVisible" to Str.translateBoolean(exercise.assessments_student_visible),
                "lastModified" to exercise.last_modified.toEstonianString(),
                "exerciseTitle" to (exercise.title_alias ?: exercise.title),
                "exerciseText" to exercise.text_html
        ))

        fl?.end()
        return exercise
    }

    private fun buildTeacherTesting() {
        val fl = debugFunStart("buildTeacherTesting")
        getElemById("testing").innerHTML = tmRender("tm-teach-exercise-testing", mapOf(
                "checkLabel" to "Kontrolli"
        ))
        CodeMirror.fromTextArea(getElemById("testing-submission"),
                objOf("mode" to "python",
                        "lineNumbers" to true,
                        "autoRefresh" to true,
                        "viewportMargin" to 100))
        fl?.end()
    }

    private suspend fun buildTeacherStudents(courseId: String, courseExerciseId: String, threshold: Int) {
        val fl = debugFunStart("buildTeacherStudents")

        val studentsPromise = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students", ReqMethod.GET)
        val studentsResp = studentsPromise.await()
        if (!studentsResp.http200) {
            errorMessage { Str.somethingWentWrong() }
            error("Fetching student submissions failed with status ${studentsResp.status}")
        }

        val teacherStudents = studentsResp.parseTo(TeacherStudents.serializer()).await()

        val studentArray = teacherStudents.students.map { student ->
            val studentMap = mutableMapOf<String, Any?>(
                    "id" to student.student_id,
                    "givenName" to student.given_name,
                    "familyName" to student.family_name,
                    "time" to student.submission_time?.toEstonianString(),
                    "points" to student.grade
            )

            when (student.graded_by) {
                GraderType.AUTO -> {
                    studentMap["evalAuto"] = true
                }
                GraderType.TEACHER -> {
                    studentMap["evalTeacher"] = true
                }
            }

            if (student.grade == null) {
                if (student.submission_time == null)
                    studentMap["unstarted"] = true
                else
                    studentMap["evalMissing"] = true
            } else {
                if (student.grade >= threshold)
                    studentMap["completed"] = true
                else
                    studentMap["started"] = true
            }

            studentMap.toJsObj()
        }.toTypedArray()

        getElemById("students").innerHTML = tmRender("tm-teach-exercise-students",
                mapOf("students" to studentArray))

        getNodelistBySelector("[data-student-id]").asList().forEach {
            if (it is Element) {
                val id = it.getAttribute("data-student-id") ?: error("No data-student-id found on student item")
                val givenName = it.getAttribute("data-given-name") ?: error("No data-given-name found on student item")
                val familyName = it.getAttribute("data-family-name") ?: error("No data-family-name found on student item")

                it.onVanillaClick {
                    debug { "$id $givenName $familyName" }
                    buildStudentTab(courseId, courseExerciseId, id, givenName, familyName)
                }

            } else {
                error("Student item is not an Element")
            }
        }

        fl?.end()
    }

    private fun buildStudentTab(courseId: String, courseExerciseId: String, studentId: String, givenName: String, familyName: String) {
        getElemById("tab-student").textContent = "$givenName ${familyName[0]}"

        val tabs = Materialize.Tabs.getInstance(getElemById("tabs"))
        tabs.select("student")
        tabs.updateTabIndicator()

        MainScope().launch {
            val submissionResp =
                    fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId/submissions/latest/students/$studentId",
                    ReqMethod.GET).await()

            if (!submissionResp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching student submission failed with status ${submissionResp.status}")
            }

            val submission = submissionResp.parseTo(TeacherSubmission.serializer()).await()

            debug { submission.toString() }


            getElemById("student").innerHTML = tmRender("tm-teach-exercise-student-submission", mapOf(

            ))
        }
    }


    private fun buildStudentExercise(courseId: String) {


    }

    data class PathIds(val courseId: String, val exerciseId: String)

    private fun extractSanitizedPathIds(path: String): PathIds {
        val match = path.match("^/courses/(\\w+)/exercises/(\\w+)/summary/?\$")
        if (match != null && match.size == 3) {
            return PathIds(match[1], match[2])
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }
}