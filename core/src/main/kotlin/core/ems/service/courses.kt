package core.ems.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.db.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import core.util.notNullAndInPast
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import java.io.Serializable

private val log = KotlinLogging.logger {}

data class SubmissionRow(
    @JsonProperty("latest_submission") val latestSubmission: LatestSubmissionResp?,
    @JsonProperty("student_id") val accountId: String,
    @JsonProperty("given_name") val accountGivenName: String,
    @JsonProperty("family_name") val accountFamilyName: String,
    @JsonProperty("groups") val groups: List<GroupResp>
) : Serializable

data class LatestSubmissionResp(
    @JsonProperty("id") val submissionId: String,
    @JsonSerialize(using = DateTimeSerializer::class) @JsonProperty("time") val time: DateTime,
    @JsonProperty("grade") val grade: GradeResp?
)

data class GroupResp(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String
)

data class StudentsResp(
    @JsonProperty("id") val id: String,
    @JsonProperty("email") val email: String,
    @JsonProperty("given_name") val givenName: String,
    @JsonProperty("family_name") val familyName: String,
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("created_at") val createdAt: DateTime?,
    @JsonProperty("groups") val groups: List<GroupResp>,
    @JsonProperty("moodle_username") val moodleUsername: String?,
)

data class CourseDTO(
    val id: Long,
    val title: String,
    val alias: String?,
    val createdAt: DateTime,
    val moodleShortName: String?,
    val moodleSyncStudents: Boolean,
    val moodleSyncGrades: Boolean,
    val moodleSyncStudentsInProgress: Boolean,
    val moodleSyncGradesInProgress: Boolean,
)

private data class ParticipantGroupDTO(val id: Long, val name: String)

private data class StudentOnCourseDTO(
    val id: String,
    val email: String,
    val givenName: String,
    val familyName: String,
    val createdAt: DateTime?,
    val moodleUsername: String?
)

fun getCourse(courseId: Long): CourseDTO? = transaction {
    Course.select {
        Course.id.eq(courseId)
    }.map {
        CourseDTO(
            it[Course.id].value,
            it[Course.title],
            it[Course.alias],
            it[Course.createdAt],
            it[Course.moodleShortName],
            it[Course.moodleSyncStudents],
            it[Course.moodleSyncGrades],
            it[Course.moodleSyncStudentsInProgress],
            it[Course.moodleSyncGradesInProgress],
        )
    }.singleOrNull()
}

// TODO: remove
fun assertCourseExists(courseId: Long) {
    if (!courseExists(courseId)) {
        throw InvalidRequestException("Course $courseId does not exist")
    }
}

fun courseExists(courseId: Long): Boolean = transaction { Course.select { Course.id eq courseId }.count() > 0 }

fun assertExerciseIsAutoGradable(exerciseId: Long) {
    val autoGradable = transaction {
        (Exercise innerJoin ExerciseVer)
            .slice(ExerciseVer.graderType)
            .select { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.graderType] }
            .single() == GraderType.AUTO
    }
    if (!autoGradable) throw InvalidRequestException(
        "Exercise $exerciseId is not automatically assessable",
        ReqError.EXERCISE_NOT_AUTOASSESSABLE
    )
}

fun toGradeRespOrNull(grade: Int?, isAuto: Boolean?, isGradedDirectly: Boolean?) = if (grade != null && isAuto != null) {
    (GradeResp(grade, isAuto, isGradedDirectly))
} else null


data class ExercisesResp(
    @JsonProperty("exercise_id") val exerciseId: String,
    @JsonProperty("library_title") val libraryTitle: String,
    @JsonProperty("title_alias") val titleAlias: String?,
    @JsonProperty("effective_title") val effectiveTitle: String,
    @JsonProperty("grade_threshold") val gradeThreshold: Int,
    @JsonProperty("student_visible") val studentVisible: Boolean,
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("student_visible_from") val studentVisibleFrom: DateTime?,
    @JsonSerialize(using = DateTimeSerializer::class)
    @JsonProperty("soft_deadline") val softDeadline: DateTime?,
    @JsonProperty("grader_type") val graderType: GraderType,
    @JsonProperty("ordering_idx") val orderingIndex: Int,
    @JsonProperty("unstarted_count") val unstartedCount: Int,
    @JsonProperty("ungraded_count") val ungradedCount: Int,
    @JsonProperty("started_count") val startedCount: Int,
    @JsonProperty("completed_count") val completedCount: Int,
    @JsonProperty("latest_submissions") @JsonInclude(JsonInclude.Include.NON_NULL) val latestSubmissions: List<SubmissionRow>,
)


/**
 * All students with or without submission on a single course for all exercises.
 */
fun selectAllCourseExercisesLatestSubmissions(courseId: Long, groupId: String? = null): List<ExercisesResp> =
    transaction {
        val courseStudents: Map<String, StudentsResp> = selectStudentsOnCourse(courseId, groupId).associateBy { it.id }
        data class ExercisesDTO(
            val exerciseId: String,
            val libraryTitle: String,
            val titleAlias: String?,
            val effectiveTitle: String,
            val gradeThreshold: Int,
            val studentVisible: Boolean,
            val studentVisibleFrom: DateTime?,
            val softDeadline: DateTime?,
            val graderType: GraderType,
            val orderingIndex: Int,
            val students: Set<String>
        )

        val exercises = (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .slice(
                CourseExercise.id,
                CourseExercise.gradeThreshold,
                CourseExercise.studentVisibleFrom,
                CourseExercise.orderIdx,
                CourseExercise.titleAlias,
                ExerciseVer.title,
                ExerciseVer.validTo,
                CourseExercise.softDeadline,
                ExerciseVer.graderType,
            )
            .select { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
            .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
            .mapIndexed { i, it ->
                ExercisesDTO(
                    it[CourseExercise.id].value.toString(),
                    it[ExerciseVer.title],
                    it[CourseExercise.titleAlias],
                    it[CourseExercise.titleAlias] ?: it[ExerciseVer.title],
                    it[CourseExercise.gradeThreshold],
                    it[CourseExercise.studentVisibleFrom].notNullAndInPast(),
                    it[CourseExercise.studentVisibleFrom],
                    it[CourseExercise.softDeadline],
                    it[ExerciseVer.graderType],
                    i,
                    courseStudents.keys
                )
            }

        val studentsWithSubmissions = (ExerciseVer innerJoin Exercise innerJoin CourseExercise leftJoin Submission)
            .slice(
                DistinctOn<Any>(listOf(CourseExercise.id, Submission.student)),
                CourseExercise.id,
                Submission.student,
                Submission.id,
                Submission.createdAt,
                Submission.grade,
                Submission.isAutoGrade,
                Submission.isGradedDirectly
            )
            .select {
                CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() and Submission.student.inList(courseStudents.keys)
            }.orderBy(
                CourseExercise.id to SortOrder.DESC,
                Submission.student to SortOrder.DESC,
                Submission.createdAt to SortOrder.DESC
            ).mapNotNull {
                val submissionId = it[Submission.id]?.value

                if (submissionId == null) null else {
                    val studentId = it[Submission.student].value
                    val student = courseStudents[studentId] ?: throw IllegalStateException()

                    val grade = toGradeRespOrNull(it[Submission.grade], it[Submission.isAutoGrade], it[Submission.isGradedDirectly])
                    val latest = LatestSubmissionResp(submissionId.toString(), it[Submission.createdAt], grade)

                    (studentId to it[CourseExercise.id].value.toString()) to SubmissionRow(latest, studentId, student.givenName, student.familyName, student.groups)
                }
            }.toMap()


        exercises.map { ex ->

            val studentSubmissionRows = ex.students.map { id ->
                val student = courseStudents[id] ?: throw IllegalStateException()
                studentsWithSubmissions[id to ex.exerciseId] ?: SubmissionRow(null, id, student.givenName, student.familyName, student.groups)
            }

            val latestSubmissionValidGrades = studentSubmissionRows.mapNotNull { it.latestSubmission }.map { it.grade?.grade }
            val unstartedCount = studentSubmissionRows.size - latestSubmissionValidGrades.size
            val ungradedCount = latestSubmissionValidGrades.count { it == null }
            val startedCount = latestSubmissionValidGrades.count { it != null && it < ex.gradeThreshold }
            val completedCount = latestSubmissionValidGrades.count { it != null && it >= ex.gradeThreshold }

            // Sanity check
            if (unstartedCount + ungradedCount + startedCount + completedCount != studentSubmissionRows.size)
                log.warn {
                    "Student grade sanity check failed. unstarted: $unstartedCount, ungraded: $ungradedCount, " +
                            "started: $startedCount, completed: $completedCount, students in course: $studentSubmissionRows"
                }


            ExercisesResp(
                ex.exerciseId,
                ex.libraryTitle,
                ex.titleAlias,
                ex.effectiveTitle,
                ex.gradeThreshold,
                ex.studentVisible,
                ex.studentVisibleFrom,
                ex.softDeadline,
                ex.graderType,
                ex.orderingIndex,
                unstartedCount,
                ungradedCount,
                startedCount,
                completedCount,
                studentSubmissionRows
            )
        }
    }

fun selectStudentsOnCourse(courseId: Long, groupId: String? = null): List<StudentsResp> = transaction {
    (Account innerJoin StudentCourseAccess leftJoin StudentCourseGroup leftJoin CourseGroup)
        .slice(
            Account.id, Account.email, Account.givenName, Account.familyName, Account.moodleUsername,
            StudentCourseAccess.createdAt, CourseGroup.id, CourseGroup.name
        ).select {
            StudentCourseAccess.course eq courseId
        }
        .groupBy({
            StudentOnCourseDTO(
                it[Account.id].value,
                it[Account.email],
                it[Account.givenName],
                it[Account.familyName],
                it[StudentCourseAccess.createdAt],
                it[Account.moodleUsername]
            )
        }) {
            val groupId: EntityID<Long>? = it[CourseGroup.id]
            if (groupId != null) ParticipantGroupDTO(groupId.value, it[CourseGroup.name]) else null
        }
        .map { (student, groups) ->
            student to groups.filterNotNull()
        }
        .map { (student, groups) ->
            StudentsResp(
                student.id,
                student.email,
                student.givenName,
                student.familyName,
                student.createdAt,
                groups.map { GroupResp(it.id.toString(), it.name) },
                student.moodleUsername
            )
        }
        // TODO: probably can integrate into QUERY.
        .filter { if(groupId == null) true else it.groups.map { g -> g.id }.contains(groupId) }
}
