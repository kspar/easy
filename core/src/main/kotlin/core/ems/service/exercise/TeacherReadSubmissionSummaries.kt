package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

// TODO: waiting for NULLS FIRST/LAST support (https://github.com/JetBrains/Exposed/issues/478)
private enum class OrderBy {
    FAMILY_NAME,
    // latest - earliest - no submission
    // no submission - earliest - latest
    SUBMISSION_TIME,
    // auto, teacher, missing
    // teacher, auto, missing
    GRADED_BY,
    // high, low, missing (nulls last)
    // missing, low, high (nulls first)
    GRADE
}

@RestController
@RequestMapping("/v2")
class TeacherReadSubmissionSummariesController {

    data class Resp(@JsonProperty("student_id") val studentId: String,
                    @JsonProperty("given_name") val studentGivenName: String,
                    @JsonProperty("family_name") val studentFamilyName: String,
                    @JsonSerialize(using = DateTimeSerializer::class)
                    @JsonProperty("submission_time") val submissionTime: DateTime?,
                    @JsonProperty("grade") val grade: Int?,
                    @JsonProperty("graded_by") val gradedBy: GraderType?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @PathVariable("courseExerciseId") courseExerciseIdString: String,
                   @RequestParam("search", required = false, defaultValue = "") searchString: String,
                   @RequestParam("orderby", required = false) orderByString: String?,
                   @RequestParam("order", required = false) orderString: String?,
                   caller: EasyUser): List<Resp> {

        log.debug {
            "Getting submission summaries for ${caller.id} on course exercise $courseExerciseIdString " +
                    "on course $courseIdString (search string: '$searchString', orderby: $orderByString, order: $orderString)"
        }
        val courseId = courseIdString.idToLongOrInvalidReq()

        val orderBy = when (orderByString) {
            "name" -> OrderBy.FAMILY_NAME
            "time" -> OrderBy.SUBMISSION_TIME
            "gradedby" -> OrderBy.GRADED_BY
            "grade" -> OrderBy.GRADE
            null -> OrderBy.FAMILY_NAME
            else -> throw InvalidRequestException("Invalid value for orderby parameter", ReqError.INVALID_PARAMETER_VALUE)
        }
        val order = when (orderString) {
            "asc" -> SortOrder.ASC
            "desc" -> SortOrder.DESC
            null -> SortOrder.ASC
            else -> throw InvalidRequestException("Invalid value for order parameter", ReqError.INVALID_PARAMETER_VALUE)
        }

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val queryWords = searchString.trim().toLowerCase().split(" ").filter { it.isNotEmpty() }

        return selectTeacherSubmissionSummaries(
                courseId, courseExerciseIdString.idToLongOrInvalidReq(), queryWords, orderBy, order)
    }
}

private fun selectTeacherSubmissionSummaries(courseId: Long, courseExId: Long, queryWords: List<String>,
                                             orderBy: OrderBy, order: SortOrder):
        List<TeacherReadSubmissionSummariesController.Resp> {
    return transaction {
        addLogger(StdOutSqlLogger) // TODO: remove

        // Alias is needed on distinctOn for some reason
        val distinctStudentId = Student.id.distinctOn().alias("student_id")
        // Prevent teacher and auto grade name clash
        val autoGradeAlias = AutomaticAssessment.grade.alias("autograde")
        val validGradeAlias = Coalesce(TeacherAssessment.grade, AutomaticAssessment.grade).alias("real_grade")

        val subQuery = (StudentCourseAccess innerJoin Student leftJoin
                (Submission innerJoin CourseExercise leftJoin AutomaticAssessment leftJoin TeacherAssessment))
                .slice(distinctStudentId, Student.givenName, Student.familyName, Submission.createdAt,
                        autoGradeAlias, TeacherAssessment.grade, validGradeAlias)
                .select {
                    // CourseExercise.id & CourseExercise.course are null when the student has no submission
                    (CourseExercise.id eq courseExId or CourseExercise.id.isNull()) and
                            (CourseExercise.course eq courseId or CourseExercise.course.isNull()) and
                            (StudentCourseAccess.course eq courseId)
                }

        queryWords.forEach {
            subQuery.andWhere {
                (Student.id like "%$it%") or
                        (Student.givenName.lowerCase() like "%$it%") or
                        (Student.familyName.lowerCase() like "%$it%")
            }
        }

        val subTable = subQuery.orderBy(distinctStudentId to SortOrder.ASC,
                Submission.createdAt to SortOrder.DESC,
                AutomaticAssessment.createdAt to SortOrder.DESC,
                TeacherAssessment.createdAt to SortOrder.DESC)
                .alias("t")

        val wrapQuery = subTable
                // Slice is needed because aliased columns are not included by default
                .slice(subTable[distinctStudentId], subTable[Student.givenName], subTable[Student.familyName],
                        subTable[Submission.createdAt], subTable[TeacherAssessment.grade], subTable[autoGradeAlias],
                        subTable[validGradeAlias])
                .selectAll()

        when (orderBy) {
            OrderBy.FAMILY_NAME -> wrapQuery.orderBy(subTable[Student.familyName] to order)
            OrderBy.SUBMISSION_TIME -> wrapQuery.orderBy(subTable[Submission.createdAt] to order)
            OrderBy.GRADED_BY -> wrapQuery.orderBy(subTable[autoGradeAlias] to order, subTable[TeacherAssessment.grade] to order)
            OrderBy.GRADE -> wrapQuery.orderBy(subTable[validGradeAlias] to order)
        }

        wrapQuery.map {
            // Explicit nullable types because exposed's type system seems to fail here: it's assuming
            // non-nullable types as they are in the table, does not account for left joins that create nulls
            val autoGrade: Int? = it[subTable[autoGradeAlias]]
            val teacherGrade: Int? = it[subTable[TeacherAssessment.grade]]
            val validGrade: Int? = it[subTable[validGradeAlias]]

            val validGradePair = when {
                teacherGrade != null -> teacherGrade to GraderType.TEACHER
                autoGrade != null -> autoGrade to GraderType.AUTO
                else -> null
            }

            if (validGrade != validGradePair?.first) {
                log.warn { "Valid grade is incorrect. From db: $validGrade, real: $validGradePair" }
            }

            TeacherReadSubmissionSummariesController.Resp(
                    it[subTable[distinctStudentId]].value,
                    it[subTable[Student.givenName]],
                    it[subTable[Student.familyName]],
                    it[subTable[Submission.createdAt]],
                    validGradePair?.first,
                    validGradePair?.second
            )
        }
    }
}
