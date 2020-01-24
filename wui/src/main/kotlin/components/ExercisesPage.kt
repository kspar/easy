package components

import Auth
import DateSerializer
import PageName
import Role
import Str
import debug
import debugFunStart
import getContainer
import getNodelistBySelector
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import libheaders.Materialize
import queries.*
import tmRender
import toEstonianString
import toJsObj
import kotlin.browser.window
import kotlin.js.Date
import kotlin.math.max


object ExercisesPage : EasyPage() {

    enum class GraderType {
        AUTO, TEACHER
    }

    enum class ExerciseStatus {
        UNSTARTED, STARTED, COMPLETED
    }

    @Serializable
    data class StudentExercises(val exercises: List<StudentExercise>)

    @Serializable
    data class StudentExercise(val id: String,
                               val effective_title: String,
                               @Serializable(with = DateSerializer::class)
                               val deadline: Date?,
                               val status: ExerciseStatus,
                               val grade: Int?,
                               val graded_by: GraderType?,
                               val ordering_idx: Int)

    @Serializable
    data class TeacherExercises(val exercises: List<TeacherExercise>)

    @Serializable
    data class TeacherExercise(val id: String,
                               val effective_title: String,
                               @Serializable(with = DateSerializer::class)
                               val soft_deadline: Date?,
                               val grader_type: GraderType,
                               val ordering_idx: Int,
                               val unstarted_count: Int,
                               val ungraded_count: Int,
                               val started_count: Int,
                               val completed_count: Int)


    override val pageName: PageName
        get() = PageName.EXERCISES

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/exercises/?$")

    override fun clear() {
        super.clear()
        getContainer().innerHTML = tmRender("tm-loading-placeholders",
                mapOf("marginTopRem" to 6, "titleWidthRem" to 30))
    }

    override fun build(pageStateStr: String?) {
        val funLog = debugFunStart("ExercisesPage.build")

        val courseId = extractSanitizedCourseId(window.location.pathname)
        debug { "Course ID: $courseId" }

        when (Auth.activeRole) {
            Role.STUDENT -> buildStudentExercises(courseId)
            Role.TEACHER, Role.ADMIN -> buildTeacherExercises(courseId)
        }

        funLog?.end()
    }

    private fun extractSanitizedCourseId(path: String): String {
        val match = path.match("^/courses/(\\w+)/exercises/?$")
        if (match != null && match.size == 2) {
            return match[1]
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }

    private fun buildTeacherExercises(courseId: String) {
        MainScope().launch {
            val exercisesPromise = fetchEms("/teacher/courses/$courseId/exercises", ReqMethod.GET,
                    successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessPage))
            val courseInfoPromise = BasicCourseInfo.get(courseId)
            val exercisesResp = exercisesPromise.await()

            val courseTitle = courseInfoPromise.await().title
            val exercises = exercisesResp.parseTo(TeacherExercises.serializer()).await()

            val exerciseArray = exercises.exercises
                    .sortedBy { it.ordering_idx }
                    .map { ex ->
                        val exMap = mutableMapOf<String, Any>(
                                "href" to "/courses/$courseId/exercises/${ex.id}/summary",
                                "title" to ex.effective_title,
                                "deadlineLabel" to Str.deadlineLabel(),
                                "completedLabel" to Str.completedLabel(),
                                "startedLabel" to Str.startedLabel(),
                                "ungradedLabel" to Str.ungradedLabel(),
                                "unstartedLabel" to Str.unstartedLabel()
                        )

                        ex.soft_deadline?.let {
                            exMap["deadline"] = it.toEstonianString()
                        }

                        exMap["studentsExist"] = ex.completed_count + ex.started_count + ex.ungraded_count + ex.unstarted_count != 0

                        val counts = StudentCounts(ex.completed_count, ex.started_count, ex.ungraded_count, ex.unstarted_count)
                        val shares = calculateStudentShares(counts)

                        exMap["completedPc"] = shares.completedShare * 100
                        exMap["startedPc"] = shares.startedShare * 100
                        exMap["ungradedPc"] = shares.ungradedShare * 100
                        exMap["unstartedPc"] = shares.unstartedShare * 100

                        exMap["completedCount"] = ex.completed_count
                        exMap["startedCount"] = ex.started_count
                        exMap["ungradedCount"] = ex.ungraded_count
                        exMap["unstartedCount"] = ex.unstarted_count

                        if (shares.completedShare + shares.startedShare + shares.ungradedShare + shares.unstartedShare == 0.0)
                            exMap["noBb"] = true

                        exMap.toJsObj()
                    }.toTypedArray()

            getContainer().innerHTML = tmRender("tm-teach-exercises-list", mapOf(
                    "courses" to Str.myCourses(),
                    "coursesHref" to "/courses",
                    "courseId" to courseId,
                    "gradesLabel" to Str.gradesLabel(),
                    "title" to courseTitle,
                    "exercises" to exerciseArray
            ))

            Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
        }
    }

    data class StudentCounts(val completedCount: Int, val startedCount: Int, val ungradedCount: Int, val unstartedCount: Int)
    data class StudentShares(val completedShare: Double, val startedShare: Double, val ungradedShare: Double, val unstartedShare: Double)

    private fun calculateStudentShares(counts: StudentCounts): StudentShares {
        val studentCount = counts.completedCount + counts.startedCount + counts.ungradedCount + counts.unstartedCount

        val correctedShares = mapOf("completed" to counts.completedCount,
                "started" to counts.startedCount,
                "ungraded" to counts.ungradedCount,
                "unstarted" to counts.unstartedCount)
                .filter { it.value > 0 }
                .map { it.key to it.value.toDouble() / studentCount }
                .map { it.first to max(it.second, 0.01) }.toMap()

        val overCorrection = correctedShares.values.sum() - 1

        // Account for overcorrection by subtracting the total overcorrection from all shares respecting each share,
        // also add 0.1% according to the shares to compensate for inaccurate browser floating-point width calculations
        val backcorrectedShares = correctedShares
                .map { it.key to it.value - it.value * 0.97 * overCorrection + 0.001 / correctedShares.size }.toMap()

        return StudentShares(backcorrectedShares["completed"] ?: 0.0,
                backcorrectedShares["started"] ?: 0.0,
                backcorrectedShares["ungraded"] ?: 0.0,
                backcorrectedShares["unstarted"] ?: 0.0)
    }

    private fun buildStudentExercises(courseId: String) {
        MainScope().launch {
            val courseInfoPromise = BasicCourseInfo.get(courseId)
            val exercisesResp = fetchEms("/student/courses/$courseId/exercises", ReqMethod.GET,
                    successChecker = { http200 }, errorHandlers = listOf(ErrorHandlers.noCourseAccessPage)).await()

            val courseTitle = courseInfoPromise.await().title
            val exercises = exercisesResp.parseTo(StudentExercises.serializer()).await()

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

            getContainer().innerHTML = tmRender("tm-stud-exercises-list", mapOf(
                    "courses" to Str.myCourses(),
                    "coursesHref" to "/courses",
                    "title" to courseTitle,
                    "exercises" to exerciseArray
            ))

            Materialize.Tooltip.init(getNodelistBySelector(".tooltipped"))
        }
    }
}
