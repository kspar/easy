package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.getTeacherRestrictedGroups
import core.ems.service.idToLongOrInvalidReq
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

        val restrictedGroups = getTeacherRestrictedGroups(courseId, caller.id)

        return selectGradesResponse(courseId, offsetStr?.toIntOrNull(), limitStr?.toIntOrNull(), queryWords, restrictedGroups)
    }
}

private fun isCoursePresent(courseId: Long): Boolean {
    return transaction {
        Course.select {
            Course.id eq courseId
        }.count() > 0
    }
}

private fun selectGradesResponse(courseId: Long, offset: Int?, limit: Int?, queryWords: List<String>,
                                 restrictedGroups: List<Long>): TeacherReadGradesController.Resp {
    return transaction {
        val studentsQuery = selectStudentsOnCourseQuery(courseId, queryWords, restrictedGroups)
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

private fun selectStudentsOnCourseQuery(courseId: Long, queryWords: List<String>, restrictedGroups: List<Long>): Query {
    val query = (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentGroupAccess)
            .slice(Student.id, Account.email, Account.givenName, Account.familyName)
            .select { StudentCourseAccess.course eq courseId }
            .withDistinct()

    if (restrictedGroups.isNotEmpty()) {
        query.andWhere {
            StudentGroupAccess.group inList restrictedGroups or
                    (StudentGroupAccess.group.isNull())
        }
    }

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
                            selectLatestGradesForCourseExercise(ex[CourseExercise.id].value, studentIds)
                    )
                }
    }
}


fun selectLatestGradesForCourseExercise(courseExerciseId: Long, studentIds: List<String>): List<TeacherReadGradesController.GradeResp> {
    return transaction {
        (Submission.leftJoin(TeacherAssessment)).leftJoin(AutomaticAssessment)
                .slice(Submission.id,
                        Submission.student,
                        TeacherAssessment.id,
                        TeacherAssessment.grade,
                        TeacherAssessment.feedback,
                        AutomaticAssessment.id,
                        AutomaticAssessment.grade,
                        AutomaticAssessment.feedback)
                .select { Submission.courseExercise eq courseExerciseId and (Submission.student inList studentIds) }
                .orderBy(Submission.createdAt, SortOrder.DESC)
                .distinctBy { Submission.student }
                .mapNotNull {
                    when {
                        it[TeacherAssessment.id] != null -> TeacherReadGradesController.GradeResp(
                                it[Submission.student].value,
                                it[TeacherAssessment.grade],
                                GraderType.TEACHER,
                                it[TeacherAssessment.feedback])
                        it[AutomaticAssessment.id] != null -> TeacherReadGradesController.GradeResp(
                                it[Submission.student].value,
                                it[AutomaticAssessment.grade],
                                GraderType.AUTO,
                                it[AutomaticAssessment.feedback])
                        else -> null
                    }
                }
    }
}
