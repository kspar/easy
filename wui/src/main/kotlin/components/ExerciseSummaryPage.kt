package components

import DateSerializer
import PageName
import Str
import debug
import errorMessage
import fetchEms
import getContainer
import getElemById
import getElemsByClass
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.CodeMirror
import libheaders.Materialize
import objOf
import parseTo
import queries.BasicCourseInfo
import tmRender
import toEstonianString
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


    override val pageName: Any
        get() = PageName.EXERCISE_SUMMARY

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises/\\w+/summary/?$")

    override fun build(pageStateStr: String?) {

        val pathIds = extractSanitizedPathIds(window.location.pathname)
        val courseId = pathIds.courseId
        val courseExerciseId = pathIds.exerciseId

        MainScope().launch {

            val exercisePromise = fetchEms("/teacher/courses/$courseId/exercises/$courseExerciseId", ReqMethod.GET)

            val courseTitle = BasicCourseInfo.get(pathIds.courseId).await().title

            val exerciseResp = exercisePromise.await()
            if (!exerciseResp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching exercises failed with status ${exerciseResp.status}")
            }

            val exercise = exerciseResp.parseTo(TeacherExercise.serializer()).await()
            debug { "Exercise: $exercise" }

            getContainer().innerHTML = tmRender("tm-teach-exercise", mapOf(
                    "coursesHref" to "/courses",
                    "courseHref" to "/courses/${pathIds.courseId}/exercises",
                    "courses" to "Minu kursused",
                    "courseTitle" to courseTitle,
                    "exerciseTitle" to (exercise.title_alias ?: exercise.title),
                    "exerciseLabel" to "Ülesanne",
                    "testingLabel" to "Katsetamine",
                    "studentSubmLabel" to "Esitused",
                    "softDeadline" to (exercise.soft_deadline?.toEstonianString() ?: ""),
                    "hardDeadline" to (exercise.hard_deadline?.toEstonianString() ?: ""),
                    "graderType" to if (exercise.grader_type == GraderType.AUTO) "automaatne" else "käsitsi",
                    "threshold" to exercise.threshold,
                    "studentVisible" to if (exercise.student_visible) "jah" else "ei",
                    "assStudentVisible" to if (exercise.assessments_student_visible) "jah" else "ei",
                    "lastModified" to exercise.last_modified.toEstonianString(),
                    "exerciseText" to (exercise.text_html ?: ""),
                    "checkLabel" to "Kontrolli"
            ))

            Materialize.Tabs.init(getElemsByClass("tabs")[0])

            CodeMirror.fromTextArea(getElemById("testing-submission"),
                    objOf("mode" to "python",
                            "lineNumbers" to true,
                            "autoRefresh" to true,
                            "viewportMargin" to 100))
        }

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