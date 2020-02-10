package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.*
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadGradesController(val courseService: CourseService) {

    data class Resp(@JsonProperty("student_count") val studentCount: Int,
                    @JsonProperty("students")
                    @JsonInclude(Include.NON_NULL) val students: List<StudentsResp>,
                    @JsonProperty("exercises")
                    @JsonInclude(Include.NON_NULL) val exercises: List<ExercisesResp>)

    data class StudentsResp(@JsonProperty("student_id") val studentId: String,
                            @JsonProperty("given_name") val givenName: String,
                            @JsonProperty("family_name") val familyName: String,
                            @JsonProperty("email") val email: String)

    data class ExercisesResp(@JsonProperty("exercise_id") val exerciseId: String,
                             @JsonProperty("effective_title") val effectiveTitle: String,
                             @JsonProperty("grade_threshold") val gradeThreshold: Int,
                             @JsonProperty("student_visible") val studentVisible: Boolean,
                             @JsonProperty("grades") @JsonInclude(Include.NON_NULL) val grades: List<GradeResp>)

    data class GradeResp(@JsonProperty("student_id") val studentId: String,
                         @JsonProperty("grade") @JsonInclude(Include.NON_NULL) val grade: Int?,
                         @JsonProperty("grader_type") @JsonInclude(Include.NON_NULL) val graderType: GraderType?,
                         @JsonProperty("feedback") @JsonInclude(Include.NON_NULL) val feedback: String?)


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/teacher/{courseId}/grades")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestParam("offset", required = false) offsetStr: String?,
                   @RequestParam("limit", required = false) limitStr: String?,
                   @RequestParam("search", required = false, defaultValue = "") search: String,
                   caller: EasyUser): Resp {

        log.debug { "Getting grades for ${caller.id} on course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        assertCourseExists(courseId)

        val queryWords = search.trim().toLowerCase().split(Regex(" +"))

        val restrictedGroups = getTeacherRestrictedGroups(courseId, caller.id)

        return selectGradesResponse(courseId, offsetStr?.toIntOrNull(), limitStr?.toIntOrNull(), queryWords, restrictedGroups, courseService)
    }
}


private fun selectGradesResponse(
        courseId: Long,
        offset: Int?,
        limit: Int?,
        queryWords: List<String>,
        restrictedGroups: List<Long>,
        courseService: CourseService
): TeacherReadGradesController.Resp {

    return transaction {
        val studentsQuery = courseService.selectStudentsOnCourseQuery(courseId, queryWords, restrictedGroups)
        val studentCount = studentsQuery.count()
        val students = studentsQuery
                .limit(limit ?: studentCount, offset ?: 0)
                .map {
                    TeacherReadGradesController.StudentsResp(
                            it[Student.id].value,
                            it[Account.givenName],
                            it[Account.familyName],
                            it[Account.email]
                    )
                }

        val exercises = selectExercisesOnCourse(courseId, students.map { it.studentId }, courseService)
        TeacherReadGradesController.Resp(studentCount, students, exercises)
    }
}


private fun selectExercisesOnCourse(
        courseId: Long,
        studentIds: List<String>,
        courseService: CourseService
): List<TeacherReadGradesController.ExercisesResp> {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.id,
                        CourseExercise.gradeThreshold,
                        CourseExercise.studentVisible,
                        CourseExercise.orderIdx,
                        ExerciseVer.title,
                        ExerciseVer.validTo,
                        CourseExercise.titleAlias)
                .select { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
                .orderBy(CourseExercise.orderIdx to SortOrder.ASC)
                .map { ex ->
                    TeacherReadGradesController.ExercisesResp(
                            ex[CourseExercise.id].value.toString(),
                            ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                            ex[CourseExercise.gradeThreshold],
                            ex[CourseExercise.studentVisible],
                            courseService.selectLatestValidGrades(ex[CourseExercise.id].value, studentIds)
                                    .map {
                                        TeacherReadGradesController.GradeResp(
                                                it.studentId,
                                                it.grade,
                                                it.graderType,
                                                it.feedback)
                                    }
                    )
                }
    }
}
