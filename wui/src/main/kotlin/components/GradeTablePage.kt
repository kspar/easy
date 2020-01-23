package components

import PageName
import ReqMethod
import Role
import Str
import errorMessage
import fetchEms
import getContainer
import http200
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import objOf
import parseTo
import queries.BasicCourseInfo
import tmRender
import kotlin.browser.window

object GradeTablePage : EasyPage() {

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
            val grade: Int,
            val grader_type: GraderType,
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

        MainScope().launch {
            val gradesPromise = fetchEms("/courses/teacher/$courseId/grades", ReqMethod.GET)
            val courseInfoPromise = BasicCourseInfo.get(courseId)

            val resp = gradesPromise.await()
            val courseTitle = courseInfoPromise.await().title

            if (!resp.http200) {
                errorMessage { Str.somethingWentWrong() }
                error("Fetching grades failed with status ${resp.status}")
            }

            val gradeTable = resp.parseTo(GradeTable.serializer()).await()

            val exercises = gradeTable.exercises.map {
                objOf("exerciseTitle" to it.effective_title)
            }.toTypedArray()

            val allStudents = gradeTable.students.map { it.student_id }

            data class GradeWithInfo(val grade: String, val status: ExerciseStatus)

            val gradesMap = mutableMapOf<String, MutableList<GradeWithInfo>>()
            gradeTable.exercises.forEach { ex ->
                ex.grades.forEach {
                    val status = if (it.grade >= ex.grade_threshold) ExerciseStatus.FINISHED else ExerciseStatus.UNFINISHED
                    gradesMap.getOrPut(it.student_id) { mutableListOf() }
                            .add(GradeWithInfo(it.grade.toString(), status))
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
                            "unfinished" to (it.status == ExerciseStatus.UNFINISHED),
                            "finished" to (it.status == ExerciseStatus.FINISHED)
                    )
                }.toTypedArray()

                objOf(
                        "name" to "${student.given_name} ${student.family_name}",
                        "grades" to grades
                )
            }.toTypedArray()

            getContainer().innerHTML = tmRender("tm-teach-gradetable", mapOf(
                    "myCoursesLabel" to Str.myCourses(),
                    "courseHref" to "/courses/$courseId/exercises",
                    "title" to courseTitle,
                    "gradesLabel" to Str.gradesLabel(),
                    "exercises" to exercises,
                    "students" to students
            ))
        }
    }

    private fun extractSanitizedCourseId(path: String): String {
        val match = path.match("^/courses/(\\w+)/grades/?$")
        if (match != null && match.size == 2) {
            return match[1]
        } else {
            error("Unexpected match on path: ${match?.joinToString()}")
        }
    }
}
