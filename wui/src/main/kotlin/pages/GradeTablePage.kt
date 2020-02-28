package pages

import PageName
import Role
import Str
import getContainer
import getElemsByClass
import isNotNullAndTrue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import objOf
import onVanillaClick
import queries.*
import tmRender
import kotlin.browser.window
import kotlin.math.min

object GradeTablePage : EasyPage() {

    private const val PAGE_STEP: Int = 50

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

    override val allowedRoles: List<Role>
        get() = listOf(Role.TEACHER, Role.ADMIN)

    override fun pathMatches(path: String) =
            path.matches("^/courses/\\w+/grades/?$")

    override fun build(pageStateStr: String?) {
        val courseId = extractSanitizedCourseId(window.location.pathname)
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

            data class PaginationConf(val pageStart: Int, val pageEnd: Int, val pageTotal: Int, val canGoBack: Boolean, val canGoForward: Boolean)

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

private fun getLastPageOffset(totalCount: Int, step: Int): Int {
    val itemsOnLastPageRaw = totalCount % step
    val itemsOnLastPage = when {
        itemsOnLastPageRaw == 0 && totalCount == 0 -> 0
        itemsOnLastPageRaw == 0 -> step
        else -> itemsOnLastPageRaw
    }
    return totalCount - itemsOnLastPage
}

private fun extractSanitizedCourseId(path: String): String {
    val match = path.match("^/courses/(\\w+)/grades/?$")
    if (match != null && match.size == 2) {
        return match[1]
    } else {
        error("Unexpected match on path: ${match?.joinToString()}")
    }
}

