package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.db.*
import core.ems.service.exercise.getStudentExerciseStatus
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeSerializer
import core.util.notNullAndInPast
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import java.io.Serializable
import java.security.SecureRandom

private val log = KotlinLogging.logger {}

data class SubmissionRow(
    @get:JsonProperty("submission") val latestSubmission: LatestSubmissionResp?,
    @get:JsonProperty("status") val status: StudentExerciseStatus,
    @get:JsonProperty("student_id") val accountId: String,
    @get:JsonProperty("given_name") val accountGivenName: String,
    @get:JsonProperty("family_name") val accountFamilyName: String,
    @get:JsonProperty("groups") val groups: List<GroupResp>
) : Serializable

data class LatestSubmissionResp(
    @get:JsonProperty("id") val submissionId: String,
    @get:JsonProperty("submission_number") val submissionNumber: Int,
    @get:JsonSerialize(using = DateTimeSerializer::class) @get:JsonProperty("time") val time: DateTime,
    @get:JsonProperty("grade") val grade: GradeResp?,
    @get:JsonProperty("seen") val seen: Boolean,
)

data class GroupResp(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("name") val name: String
)

data class StudentsResp(
    @get:JsonProperty("id") val id: String,
    @get:JsonProperty("email") val email: String,
    @get:JsonProperty("given_name") val givenName: String,
    @get:JsonProperty("family_name") val familyName: String,
    @get:JsonSerialize(using = DateTimeSerializer::class)
    @get:JsonProperty("created_at") val createdAt: DateTime?,
    @get:JsonProperty("groups") val groups: List<GroupResp>,
    @get:JsonProperty("moodle_username") val moodleUsername: String?,
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

fun generateInviteId(length: Int): String {
    val secureRandom = SecureRandom()
    val alphabet = ('A'..'Z')
    return (1..length).map { alphabet.elementAt(secureRandom.nextInt(alphabet.count())) }.joinToString("")
}


fun getCourse(courseId: Long): CourseDTO? = transaction {
    Course.selectAll().where { Course.id.eq(courseId) }.map {
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

data class Titles(val exerciseTitle: String, val courseTitle: String)

fun getCourseAndExerciseTitles(courseId: Long, courseExId: Long): Titles = transaction {
    (Course innerJoin CourseExercise innerJoin Exercise innerJoin ExerciseVer)
        .select(Course.title, Course.alias, ExerciseVer.title, CourseExercise.titleAlias)
        .where {
            Course.id eq courseId and (CourseExercise.course eq courseId) and
                    (CourseExercise.id eq courseExId) and
                    ExerciseVer.validTo.isNull()
        }
        .map {
            Titles(
                it[CourseExercise.titleAlias] ?: it[ExerciseVer.title],
                it[Course.alias] ?: it[Course.title]
            )
        }
        .singleOrInvalidRequest()
}

// TODO: remove
fun assertCourseExists(courseId: Long) {
    if (!courseExists(courseId)) {
        throw InvalidRequestException("Course $courseId does not exist")
    }
}

fun courseExists(courseId: Long): Boolean =
    transaction { Course.selectAll().where { Course.id eq courseId }.count() > 0 }

fun assertExerciseIsAutoGradable(exerciseId: Long) {
    val autoGradable = transaction {
        (Exercise innerJoin ExerciseVer)
            .select(ExerciseVer.graderType)
            .where { Exercise.id eq exerciseId and ExerciseVer.validTo.isNull() }
            .map { it[ExerciseVer.graderType] }
            .single() == GraderType.AUTO
    }
    if (!autoGradable) throw InvalidRequestException(
        "Exercise $exerciseId is not automatically assessable",
        ReqError.EXERCISE_NOT_AUTOASSESSABLE
    )
}

fun toGradeRespOrNull(grade: Int?, isAuto: Boolean?, isGradedDirectly: Boolean?) =
    if (grade != null && isAuto != null && isGradedDirectly != null) {
        (GradeResp(grade, isAuto, isGradedDirectly))
    } else null


data class ExercisesResp(
    @get:JsonProperty("course_exercise_id") val courseExerciseId: String,
    @get:JsonProperty("exercise_id") val exerciseId: String,
    @get:JsonProperty("library_title") val libraryTitle: String,
    @get:JsonProperty("title_alias") val titleAlias: String?,
    @get:JsonProperty("effective_title") val effectiveTitle: String,
    @get:JsonProperty("grade_threshold") val gradeThreshold: Int,
    @get:JsonProperty("student_visible") val studentVisible: Boolean,
    @get:JsonSerialize(using = DateTimeSerializer::class)
    @get:JsonProperty("student_visible_from") val studentVisibleFrom: DateTime?,
    @get:JsonSerialize(using = DateTimeSerializer::class)
    @get:JsonProperty("soft_deadline") val softDeadline: DateTime?,
    @get:JsonSerialize(using = DateTimeSerializer::class)
    @get:JsonProperty("hard_deadline") val hardDeadline: DateTime?,
    @get:JsonProperty("grader_type") val graderType: GraderType,
    @get:JsonProperty("ordering_idx") val orderingIndex: Int,
    @get:JsonProperty("unstarted_count") val unstartedCount: Int,
    @get:JsonProperty("ungraded_count") val ungradedCount: Int,
    @get:JsonProperty("started_count") val startedCount: Int,
    @get:JsonProperty("completed_count") val completedCount: Int,
    @get:JsonProperty("latest_submissions") val latestSubmissions: List<SubmissionRow>,
)


/**
 * All students with or without submission on a single course for all exercises.
 */
fun selectAllCourseExercisesLatestSubmissions(
    courseId: Long,
    courseExId: Long? = null,
    groupId: Long? = null
): List<ExercisesResp> =
    transaction {
        val courseStudents: Map<String, StudentsResp> = selectStudentsOnCourse(courseId, groupId).associateBy { it.id }

        data class ExercisesDTO(
            val courseExerciseId: String,
            val exerciseId: String,
            val libraryTitle: String,
            val titleAlias: String?,
            val effectiveTitle: String,
            val gradeThreshold: Int,
            val studentVisible: Boolean,
            val studentVisibleFrom: DateTime?,
            val softDeadline: DateTime?,
            val hardDeadline: DateTime?,
            val graderType: GraderType,
            val orderingIndex: Int,
            val students: Set<String>
        )

        val exercises = (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
            .select(
                CourseExercise.id,
                CourseExercise.exercise,
                CourseExercise.gradeThreshold,
                CourseExercise.studentVisibleFrom,
                CourseExercise.orderIdx,
                CourseExercise.titleAlias,
                ExerciseVer.title,
                ExerciseVer.validTo,
                CourseExercise.softDeadline,
                CourseExercise.hardDeadline,
                ExerciseVer.graderType
            )
            .where { CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() }
            .also {
                if (courseExId != null) {
                    it.andWhere { CourseExercise.id eq courseExId }
                }
            }
            .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
            .mapIndexed { i, it ->
                ExercisesDTO(
                    it[CourseExercise.id].value.toString(),
                    it[CourseExercise.exercise].value.toString(),
                    it[ExerciseVer.title],
                    it[CourseExercise.titleAlias],
                    it[CourseExercise.titleAlias] ?: it[ExerciseVer.title],
                    it[CourseExercise.gradeThreshold],
                    it[CourseExercise.studentVisibleFrom].notNullAndInPast(),
                    it[CourseExercise.studentVisibleFrom],
                    it[CourseExercise.softDeadline],
                    it[CourseExercise.hardDeadline],
                    it[ExerciseVer.graderType],
                    i,
                    courseStudents.keys
                )
            }

        val studentsWithSubmissions = (ExerciseVer innerJoin Exercise innerJoin CourseExercise leftJoin Submission)
            .select(
                DistinctOn<Any>(listOf(CourseExercise.id, Submission.student)),
                CourseExercise.id,
                CourseExercise.gradeThreshold,
                Submission.student,
                Submission.id,
                Submission.number,
                Submission.createdAt,
                Submission.seen,
                Submission.grade,
                Submission.isAutoGrade,
                Submission.isGradedDirectly
            ).where {
                CourseExercise.course eq courseId and ExerciseVer.validTo.isNull() and Submission.student.inList(
                    courseStudents.keys
                )
            }.also {
                if (courseExId != null) {
                    it.andWhere { CourseExercise.id eq courseExId }
                }
            }.orderBy(
                CourseExercise.id to SortOrder.DESC,
                Submission.student to SortOrder.DESC,
                Submission.createdAt to SortOrder.DESC
            ).mapNotNull {
                val submissionId = it[Submission.id]?.value

                if (submissionId == null) null else {
                    val studentId = it[Submission.student].value
                    val student = courseStudents[studentId] ?: throw IllegalStateException()

                    val grade = toGradeRespOrNull(
                        it[Submission.grade],
                        it[Submission.isAutoGrade],
                        it[Submission.isGradedDirectly]
                    )
                    val submission = LatestSubmissionResp(
                        submissionId.toString(),
                        it[Submission.number],
                        it[Submission.createdAt],
                        grade,
                        it[Submission.seen],
                    )

                    val submissionStatus =
                        getStudentExerciseStatus(true, grade?.grade, it[CourseExercise.gradeThreshold])

                    (studentId to it[CourseExercise.id].value.toString()) to SubmissionRow(
                        submission,
                        submissionStatus,
                        studentId,
                        student.givenName,
                        student.familyName,
                        student.groups
                    )
                }
            }.toMap()


        exercises.map { ex ->

            val studentSubmissionRows = ex.students.map { id ->
                val student = courseStudents[id] ?: throw IllegalStateException()
                studentsWithSubmissions[id to ex.courseExerciseId] ?: SubmissionRow(
                    null,
                    StudentExerciseStatus.UNSTARTED,
                    id,
                    student.givenName,
                    student.familyName,
                    student.groups
                )
            }

            val latestSubmissionValidGrades =
                studentSubmissionRows.mapNotNull { it.latestSubmission }.map { it.grade?.grade }
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
                ex.courseExerciseId,
                ex.exerciseId,
                ex.libraryTitle,
                ex.titleAlias,
                ex.effectiveTitle,
                ex.gradeThreshold,
                ex.studentVisible,
                ex.studentVisibleFrom,
                ex.softDeadline,
                ex.hardDeadline,
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

fun selectStudentsOnCourse(courseId: Long, groupId: Long? = null): List<StudentsResp> = transaction {
    val query = (Account innerJoin StudentCourseAccess leftJoin StudentCourseGroup leftJoin CourseGroup)
        .select(
            Account.id, Account.email, Account.givenName, Account.familyName, StudentCourseAccess.moodleUsername,
            StudentCourseAccess.createdAt, CourseGroup.id, CourseGroup.name
        ).where {
            StudentCourseAccess.course eq courseId
        }

    if (groupId != null) {
        query.andWhere { StudentCourseGroup.courseGroup eq groupId }
    }

    query
        .groupBy({
            StudentOnCourseDTO(
                it[Account.id].value,
                it[Account.email],
                it[Account.givenName],
                it[Account.familyName],
                it[StudentCourseAccess.createdAt],
                it[StudentCourseAccess.moodleUsername]
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
}


data class StudentException(
    val courseExId: Long,
    val studentId: String,
    val softDeadline: ExceptionValue?,
    val hardDeadline: ExceptionValue?,
    val studentVisibleFrom: ExceptionValue?
)

data class GroupException(
    val courseExId: Long,
    val courseGroup: Long,
    val softDeadline: ExceptionValue?,
    val hardDeadline: ExceptionValue?,
    val studentVisibleFrom: ExceptionValue?
)

data class CourseExerciseException(
    val studentExceptions: Map<Long, List<StudentException>>,
    val groupExceptions: Map<Long, List<GroupException>>
)

data class ExceptionValue(val value: DateTime?)


/**
 * Selects and groups course exercise exceptions for students and their groups.
 *
 * This function retrieves exceptions for given course exercises and students. If no students are specified (empty list),
 * it retrieves exceptions for all students within the specified course exercises.
 *
 * The function first fetches exceptions for individual students and then fetches exceptions for student groups
 * to which the students belong (or all groups related to this course exercise if students are not passed).
 * It returns a `CourseExerciseException` containing both sets of exceptions.
 *
 * @param courseExIds List of course exercise IDs for which exceptions need to be retrieved.
 * @param studentIds List of student IDs for whom exceptions need to be retrieved. If empty, exceptions for all students
 *        in the specified course exercises will be retrieved.
 * @return A `CourseExerciseException` containing two maps: one for student-specific exceptions and one for group-specific exceptions.
 */
fun selectCourseExerciseExceptions(courseExIds: List<Long>, studentIds: List<String>): CourseExerciseException =
    transaction {
        val studentConstraints = CourseExerciseExceptionStudent
            .selectAll()
            .where { CourseExerciseExceptionStudent.courseExercise inList courseExIds }
            .apply {
                if (studentIds.isNotEmpty()) andWhere { CourseExerciseExceptionStudent.student inList studentIds }
            }
            .groupBy(
                keySelector = { it[CourseExerciseExceptionStudent.courseExercise].value },
                valueTransform = {
                    val isExceptionSoftDeadline = it[CourseExerciseExceptionStudent.isExceptionSoftDeadline]
                    val isExceptionHardDeadline = it[CourseExerciseExceptionStudent.isExceptionHardDeadline]
                    val isExceptionStudentVisibleFrom = it[CourseExerciseExceptionStudent.isExceptionStudentVisibleFrom]
                    StudentException(
                        it[CourseExerciseExceptionStudent.courseExercise].value,
                        it[CourseExerciseExceptionStudent.student].value,
                        if (isExceptionSoftDeadline) ExceptionValue(it[CourseExerciseExceptionStudent.softDeadline]) else null,
                        if (isExceptionHardDeadline) ExceptionValue(it[CourseExerciseExceptionStudent.hardDeadline]) else null,
                        if (isExceptionStudentVisibleFrom) ExceptionValue(it[CourseExerciseExceptionStudent.studentVisibleFrom]) else null
                    )
                }
            )

        val studentGroups = (StudentCourseGroup innerJoin CourseGroup)
            .select(StudentCourseGroup.courseGroup)
            .where { StudentCourseGroup.student inList studentIds }
            .map { it[StudentCourseGroup.courseGroup] }

        val groupConstraints = CourseExerciseExceptionGroup
            .selectAll()
            .where { CourseExerciseExceptionGroup.courseExercise inList courseExIds }
            .apply { if (studentGroups.isNotEmpty()) andWhere { CourseExerciseExceptionGroup.courseGroup inList studentGroups } }
            .groupBy(
                keySelector = { it[CourseExerciseExceptionGroup.courseExercise].value },
                valueTransform = {
                    val isExceptionSoftDeadline = it[CourseExerciseExceptionGroup.isExceptionSoftDeadline]
                    val isExceptionHardDeadline = it[CourseExerciseExceptionGroup.isExceptionHardDeadline]
                    val isExceptionStudentVisibleFrom = it[CourseExerciseExceptionGroup.isExceptionStudentVisibleFrom]

                    GroupException(
                        it[CourseExerciseExceptionGroup.courseExercise].value,
                        it[CourseExerciseExceptionGroup.courseGroup].value,
                        if (isExceptionSoftDeadline) ExceptionValue(it[CourseExerciseExceptionGroup.softDeadline]) else null,
                        if (isExceptionHardDeadline) ExceptionValue(it[CourseExerciseExceptionGroup.hardDeadline]) else null,
                        if (isExceptionStudentVisibleFrom) ExceptionValue(it[CourseExerciseExceptionGroup.studentVisibleFrom]) else null
                    )
                }
            )

        CourseExerciseException(studentConstraints, groupConstraints)
    }

/**
 * Retrieves course exercise exceptions for a single course exercise without considering specific students.
 *
 * @param courseExId The ID of the course exercise for which to retrieve exceptions.
 * @return The `CourseExerciseException` object containing exceptions data for the specified course exercise.
 */
fun selectCourseExerciseExceptions(courseExId: Long): CourseExerciseException =
    selectCourseExerciseExceptions(listOf(courseExId), emptyList())

/**
 * Retrieves course exercise exceptions for a single course exercise and a specific student.
 *
 * @param courseExId The ID of the course exercise for which to retrieve exceptions.
 * @param studentId The ID of the student for whom to retrieve exceptions.
 * @return The `CourseExerciseException` object containing exceptions data for the specified course exercise and student.
 */
fun selectCourseExerciseExceptions(courseExId: Long, studentId: String): CourseExerciseException =
    selectCourseExerciseExceptions(listOf(courseExId), listOf(studentId))

/**
 * Retrieves course exercise exceptions for multiple course exercises and a specific student.
 *
 * @param courseExIds The list of course exercise IDs for which to retrieve exceptions.
 * @param studentId The ID of the student for whom to retrieve exceptions.
 * @return The `CourseExerciseException` object containing exceptions data for the specified course exercises and student.
 */
fun selectCourseExerciseExceptions(courseExIds: List<Long>, studentId: String): CourseExerciseException =
    selectCourseExerciseExceptions(courseExIds, listOf(studentId))

private fun CourseExerciseException.extractStudentException(courseExId: Long, studentId: String): StudentException? {
    return this.studentExceptions[courseExId]?.firstOrNull { it.studentId == studentId }
}

private fun CourseExerciseException.extractGroups(courseExId: Long): List<GroupException>? {
    return this.groupExceptions[courseExId]
}

private fun List<ExceptionValue>?.farthestValueInFutureOrNull(): DateTime? =
    this?.maxByOrNull { it.value ?: DateTime(0) }?.value

/**
 * Determines the soft deadline for a specific course exercise, prioritizing student and group exceptions
 * over a default deadline if no exceptions apply. Among multiple group exceptions, prefers the deadline
 * that is farthest in the future.
 *
 * @param exceptions The `CourseExerciseException` object containing exceptions data.
 * @param courseExId The ID of the course exercise for which to determine the soft deadline.
 * @param studentId The ID of the student for whom to check exceptions.
 * @param defaultSoftDeadline The default soft deadline to use if no student or group exceptions are found.
 * @return The determined soft deadline for the course exercise, or null if none is set.
 */
fun determineSoftDeadline(
    exceptions: CourseExerciseException,
    courseExId: Long,
    studentId: String,
    defaultSoftDeadline: DateTime?
): DateTime? {
    val studentException: ExceptionValue? = exceptions.extractStudentException(courseExId, studentId)?.softDeadline
    val groupException: List<ExceptionValue>? =
        exceptions.extractGroups(courseExId)?.mapNotNull { it.softDeadline }?.ifEmpty { null }

    return when {
        studentException != null -> studentException.value
        groupException != null -> groupException.farthestValueInFutureOrNull()
        else -> defaultSoftDeadline
    }
}


/**
 * Determines the date from which a course exercise becomes visible for a specific student,
 * prioritizing student and group exceptions over a default visible from date if no exceptions apply.
 * Among multiple group exceptions, prefers the visible from date that is farthest in the future.
 *
 * @param exceptions The `CourseExerciseException` object containing exceptions data.
 * @param courseExId The ID of the course exercise for which to determine the visible from date.
 * @param studentId The ID of the student for whom to check exceptions.
 * @param defaultVisibleFrom The default visible from date to use if no student or group exceptions are found.
 * @return The determined visible from date for the course exercise, or null if none is set.
 */
fun determineCourseExerciseVisibleFrom(
    exceptions: CourseExerciseException,
    courseExId: Long,
    studentId: String,
    defaultVisibleFrom: DateTime?
): DateTime? {
    val studentException = exceptions.extractStudentException(courseExId, studentId)?.studentVisibleFrom
    val groupException = exceptions.extractGroups(courseExId)?.mapNotNull { it.studentVisibleFrom }?.ifEmpty { null }

    return when {
        studentException != null -> studentException.value
        groupException != null -> groupException.farthestValueInFutureOrNull()
        else -> defaultVisibleFrom
    }
}


fun isCourseExerciseOpenForSubmit(
    exceptions: CourseExerciseException,
    courseExId: Long,
    studentId: String,
    defaultHardDeadline: DateTime?
): Boolean {
    val studentException = exceptions.extractStudentException(courseExId, studentId)?.hardDeadline
    val groupExceptions = exceptions.extractGroups(courseExId)?.mapNotNull { it.hardDeadline }?.ifEmpty { null }

    return when {
        studentException != null -> studentException.value?.isAfterNow ?: true
        groupExceptions != null -> groupExceptions.farthestValueInFutureOrNull()?.isAfterNow ?: true
        else -> defaultHardDeadline?.isAfterNow ?: true
    }
}


fun isCourseExerciseOpenForSubmit(courseExId: Long, studentId: String): Boolean {
    val exceptions = selectCourseExerciseExceptions(courseExId, studentId)

    val hardDeadline = transaction {
        CourseExercise
            .select(CourseExercise.hardDeadline)
            .where { CourseExercise.id eq courseExId }
            .map { it[CourseExercise.hardDeadline] }
            .singleOrNull()
    }
    return isCourseExerciseOpenForSubmit(exceptions, courseExId, studentId, hardDeadline)
}
