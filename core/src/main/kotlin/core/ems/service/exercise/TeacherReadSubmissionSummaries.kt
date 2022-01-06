package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.assertTeacherOrAdminHasAccessToCourseGroup
import core.ems.service.getTeacherRestrictedCourseGroups
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

private enum class OrderBy {
    // a - b - c (asc)
    // c - b - a (desc)
    FAMILY_NAME,
    // latest - earliest - no submission (desc)
    // no submission - earliest - latest (asc)
    SUBMISSION_TIME,
    // auto, teacher, missing (asc)
    // teacher, auto, missing (desc)
    GRADED_BY,
    // high, low, missing (desc)
    // missing, low, high (asc)
    GRADE
}

@RestController
@RequestMapping("/v2")
class TeacherReadSubmissionSummariesController {

    data class Resp(@JsonProperty("student_count") val studentCount: Long,
                    @JsonProperty("students") val students: List<StudentsResp>)

    data class StudentsResp(@JsonProperty("student_id") val studentId: String,
                            @JsonProperty("given_name") val studentGivenName: String,
                            @JsonProperty("family_name") val studentFamilyName: String,
                            @JsonSerialize(using = DateTimeSerializer::class)
                            @JsonProperty("submission_time") val submissionTime: DateTime?,
                            @JsonProperty("grade") val grade: Int?,
                            @JsonProperty("graded_by") val gradedBy: GraderType?,
                            @JsonProperty("groups") val groups: String?)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/students")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   @PathVariable("courseExerciseId") courseExerciseIdString: String,
                   @RequestParam("search", required = false, defaultValue = "") searchString: String,
                   @RequestParam("orderby", required = false) orderByString: String?,
                   @RequestParam("order", required = false) orderString: String?,
                   @RequestParam("limit", required = false) limitStr: String?,
                   @RequestParam("offset", required = false) offsetStr: String?,
                   @RequestParam("group", required = false) groupIdStr: String?,
                   caller: EasyUser): Resp {

        log.debug {
            "Getting submission summaries for ${caller.id} on course exercise $courseExerciseIdString " +
                    "on course $courseIdString (search string: '$searchString', " +
                    "orderby: $orderByString, order: $orderString, group: $groupIdStr)"
        }
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
        val groupId = groupIdStr?.idToLongOrInvalidReq()

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
        if (groupId != null) assertTeacherOrAdminHasAccessToCourseGroup(caller, courseId, groupId)

        val queryWords = searchString.trim().lowercase().split(" ").filter { it.isNotEmpty() }

        return selectTeacherSubmissionSummaries(caller, courseId, courseExId, groupId,
                queryWords, orderBy, order, offsetStr?.toLongOrNull(), limitStr?.toIntOrNull())
    }
}

private fun selectTeacherSubmissionSummaries(caller: EasyUser, courseId: Long, courseExId: Long, groupId: Long?,
                                             queryWords: List<String>, orderBy: OrderBy, order: SortOrder,
                                             offset: Long?, limit: Int?): TeacherReadSubmissionSummariesController.Resp {
    return transaction {

        // Aliases are needed on all of these
        val distinctStudentId = Student.id.distinctOn().alias("studentId")
        // Prevent teacher and auto grade name clash
        val autoGradeAlias = AutomaticAssessment.grade.alias("autoGrade")
        val validGradeAlias = Coalesce(TeacherAssessment.grade, AutomaticAssessment.grade).alias("validGrade")
        val groupsString = GroupConcat(CourseGroup.name, ", ", false).alias("groupsString")

        val subQuery = (
                Join(StudentCourseAccess leftJoin (StudentCourseGroup innerJoin CourseGroup), CourseExercise,
                        onColumn = StudentCourseAccess.course, otherColumn = CourseExercise.course) innerJoin
                        Student innerJoin Account leftJoin
                        (Submission leftJoin AutomaticAssessment leftJoin TeacherAssessment))
                .slice(distinctStudentId, Account.givenName, Account.familyName, Submission.createdAt,
                        autoGradeAlias, TeacherAssessment.grade, validGradeAlias, groupsString)
                .select {
                    (CourseExercise.id eq courseExId) and
                            (CourseExercise.course eq courseId)
                }
                // Grouping for groupsString since there can be many groups
                .groupBy(distinctStudentId, Account.givenName, Account.familyName, Submission.createdAt,
                        autoGradeAlias, TeacherAssessment.grade, validGradeAlias, AutomaticAssessment.createdAt,
                        TeacherAssessment.createdAt)
                // These ORDER BY clauses are for selecting correct first rows in DISTINCT groups
                .orderBy(distinctStudentId to SortOrder.ASC,
                        Submission.createdAt to SortOrder.DESC,
                        AutomaticAssessment.createdAt to SortOrder.DESC,
                        TeacherAssessment.createdAt to SortOrder.DESC)

        when {
            groupId != null -> subQuery.andWhere { StudentCourseGroup.courseGroup eq groupId }
            else -> {
                val restrictedGroups = getTeacherRestrictedCourseGroups(courseId, caller)
                if (restrictedGroups.isNotEmpty()) {
                    subQuery.andWhere {
                        StudentCourseGroup.courseGroup inList restrictedGroups or
                                (StudentCourseGroup.courseGroup.isNull())
                    }
                }
            }
        }

        queryWords.forEach {
            subQuery.andWhere {
                (Student.id like "%$it%") or
                        (Account.givenName.lowerCase() like "%$it%") or
                        (Account.familyName.lowerCase() like "%$it%")
            }
        }

        val subTable = subQuery.alias("t")
        val wrapQuery = subTable
                // Slice is needed because aliased columns are not included by default
                .slice(subTable[distinctStudentId], subTable[Account.givenName], subTable[Account.familyName],
                        subTable[Submission.createdAt], subTable[TeacherAssessment.grade], subTable[autoGradeAlias],
                        subTable[validGradeAlias], subTable[groupsString])
                .selectAll()

        when (orderBy) {
            OrderBy.FAMILY_NAME -> wrapQuery.orderBy(subTable[Account.familyName] to order)
            OrderBy.SUBMISSION_TIME -> wrapQuery.orderBy(
                    subTable[Submission.createdAt].isNull() to order.complement(),
                    subTable[Submission.createdAt] to order)
            OrderBy.GRADED_BY -> wrapQuery.orderBy(
                    (subTable[autoGradeAlias].isNull() and subTable[TeacherAssessment.grade].isNull()) to SortOrder.ASC,
                    subTable[autoGradeAlias] to order,
                    subTable[TeacherAssessment.grade] to order)
            OrderBy.GRADE -> wrapQuery.orderBy(
                    subTable[validGradeAlias].isNull() to order.complement(),
                    subTable[validGradeAlias] to order)
        }

        val resultCount = wrapQuery.count()

        val studentsResp =
                wrapQuery.limit(limit ?: resultCount.toInt(), offset ?: 0).map {
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

                    TeacherReadSubmissionSummariesController.StudentsResp(
                            it[subTable[distinctStudentId]].value,
                            it[subTable[Account.givenName]],
                            it[subTable[Account.familyName]],
                            it[subTable[Submission.createdAt]],
                            validGradePair?.first,
                            validGradePair?.second,
                            it[subTable[groupsString]]
                    )
                }

        TeacherReadSubmissionSummariesController.Resp(resultCount, studentsResp)
    }
}
