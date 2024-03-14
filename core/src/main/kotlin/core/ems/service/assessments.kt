package core.ems.service

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.cache.CachingService
import core.ems.service.cache.countSubmissionsInAutoAssessmentCache
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime



fun assertAssessmentControllerChecks(
    caller: EasyUser, submissionIdString: String, courseExerciseIdString: String, courseIdString: String,
): Triple<String, Long, Long> {

    val callerId = caller.id
    val courseId = courseIdString.idToLongOrInvalidReq()
    val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
    val submissionId = submissionIdString.idToLongOrInvalidReq()

    caller.assertAccess { teacherOnCourse(courseId) }

    assertSubmissionExists(submissionId, courseExId, courseId)
    return Triple(callerId, courseExId, submissionId)
}


fun insertAutoAssFailed(submissionId: Long, cachingService: CachingService, studentId: String, courseExId: Long) {
    transaction {
        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.FAILED
            if (!anyPreviousTeacherActivityContainsGrade(studentId, courseExId)) {
                it[grade] = null
                it[isAutoGrade] = true
            }
        }
    }

    cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
}

fun insertAutogradeActivity(
    newGrade: Int,
    newFeedback: String?,
    submissionId: Long,
    cachingService: CachingService,
    courseExId: Long,
    studentId: String
) {
    transaction {
        val time = DateTime.now()
        AutogradeActivity.insert {
            it[student] = studentId
            it[courseExercise] = courseExId
            it[submission] = submissionId
            it[createdAt] = time
            it[grade] = newGrade
            it[feedback] = newFeedback
        }

        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.COMPLETED
            if (!anyPreviousTeacherActivityContainsGrade(studentId, courseExId)) {
                it[grade] = newGrade
                it[isAutoGrade] = true
                it[isGradedDirectly] = true
            }
        }

        StatsSubmission.update({ StatsSubmission.submissionId eq submissionId }) {
            it[autoPoints] = newGrade
            it[autoGradedAt] = time
        }

        cachingService.invalidate(countSubmissionsInAutoAssessmentCache)
    }
}


fun selectGraderType(courseExId: Long): GraderType = transaction {
    (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
        .slice(ExerciseVer.graderType)
        .select { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
        .map { it[ExerciseVer.graderType] }
        .single()
}

fun selectAutoExId(courseExId: Long): Long? = transaction {
    (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
        .slice(ExerciseVer.autoExerciseId)
        .select { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
        .map { it[ExerciseVer.autoExerciseId] }
        .single()?.value
}

private fun anyPreviousTeacherActivityContainsGrade(studentId: String, courseExercise: Long): Boolean =
    transaction {
        TeacherActivity
            .select {
                (TeacherActivity.student eq studentId) and (TeacherActivity.courseExercise eq courseExercise) and TeacherActivity.grade.isNotNull()
            }.count() > 0
    }