package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectLatestSubmissionsForExercise
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadGradesController {

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
                         @JsonProperty("grade") val grade: Int,
                         @JsonProperty("grader_type") val graderType: GraderType,
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

        if (!isCoursePresent(courseId)) {
            throw InvalidRequestException("Course $courseId does not exist")
        }

        val queryWords = search.trim().toLowerCase().split(Regex(" +"))

        return selectGradesResponse(courseId, offsetStr?.toIntOrNull(), limitStr?.toIntOrNull(), queryWords)
    }
}

private fun isCoursePresent(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId
        }.count() > 0
    }
}

private fun selectGradesResponse(courseId: Long, offset: Int?, limit: Int?, queryWords: List<String>): TeacherReadGradesController.Resp {
    return transaction {
        val studentsQuery = selectStudentsOnCourseQuery(courseId, queryWords)
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

        val exercises = selectExercisesOnCourse(courseId, students.map { it.studentId })
        TeacherReadGradesController.Resp(studentCount, students, exercises)
    }
}

private fun selectStudentsOnCourseQuery(courseId: Long, queryWords: List<String>): Query {
    val query = (Account innerJoin Student innerJoin StudentCourseAccess)
            .slice(Student.id, Account.email, Account.givenName, Account.familyName)
            .select { StudentCourseAccess.course eq courseId }

    queryWords.forEach {
        query.andWhere {
            (Student.id like "%$it%") or
                    (Account.email.lowerCase() like "%$it%") or
                    (Account.givenName.lowerCase() like "%$it%") or
                    (Account.familyName.lowerCase() like "%$it%")
        }
    }
    return query
}

private fun selectExercisesOnCourse(courseId: Long, studentIds: List<String>): List<TeacherReadGradesController.ExercisesResp> {
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
                            selectLatestSubmissionsForExercise(ex[CourseExercise.id].value).mapNotNull {
                                selectLatestGradeForSubmission(it, studentIds)
                            }
                    )
                }
    }
}

fun selectLatestGradeForSubmission(submissionId: Long, studentIds: List<String>): TeacherReadGradesController.GradeResp? {
    val studentId = Submission
            .select { Submission.id eq submissionId }
            .map { it[Submission.student] }
            .firstOrNull()?.value.toString()

    if (!studentIds.contains(studentId)) return null

    val teacherGrade = TeacherAssessment
            .slice(TeacherAssessment.submission,
                    TeacherAssessment.createdAt,
                    TeacherAssessment.grade,
                    TeacherAssessment.feedback)
            .select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { assessment ->
                TeacherReadGradesController.GradeResp(studentId,
                        assessment[TeacherAssessment.grade],
                        GraderType.TEACHER,
                        assessment[TeacherAssessment.feedback])
            }
            .firstOrNull()

    if (teacherGrade != null)
        return teacherGrade

    return AutomaticAssessment
            .slice(AutomaticAssessment.submission,
                    AutomaticAssessment.createdAt,
                    AutomaticAssessment.grade,
                    AutomaticAssessment.feedback)
            .select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to SortOrder.DESC)
            .limit(1)
            .map { assessment ->
                TeacherReadGradesController.GradeResp(studentId,
                        assessment[AutomaticAssessment.grade],
                        GraderType.AUTO,
                        assessment[AutomaticAssessment.feedback])
            }
            .firstOrNull()
}