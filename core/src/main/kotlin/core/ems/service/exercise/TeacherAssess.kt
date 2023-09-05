package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.cache.CachingService
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.moodle.MoodleGradesSyncService
import core.exception.InvalidRequestException
import core.util.SendMailService
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Max
import javax.validation.constraints.Min
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class TeacherAssessController(
    val moodleGradesSyncService: MoodleGradesSyncService,
    val cacheService: CachingService,
    val sendMailService: SendMailService,
) {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("grade", required = true) @field:Min(0) @field:Max(100) val grade: Int,
        @JsonProperty("feedback", required = false) @field:Size(max = 100000) val feedback: String?
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/assessments")
    fun controller(
        @PathVariable("courseId") courseIdString: String,
        @PathVariable("courseExerciseId") courseExerciseIdString: String,
        @PathVariable("submissionId") submissionIdString: String,
        @Valid @RequestBody assessment: Req, caller: EasyUser
    ) {

        log.debug { "Adding teacher assessment by ${caller.id} to submission $submissionIdString on course exercise $courseExerciseIdString on course $courseIdString" }

        val callerId = caller.id
        val courseId = courseIdString.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdString.idToLongOrInvalidReq()
        val submissionId = submissionIdString.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId, true) }

        if (!submissionExists(submissionId, courseExId, courseId)) {
            throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
        }

        insertTeacherAssessment(callerId, submissionId, assessment, courseExId)
        moodleGradesSyncService.syncSingleGradeToMoodle(submissionId)
        if (assessment.feedback != null)
            sendNotification(courseId, courseExId, submissionId, assessment.feedback)
    }

    private fun submissionExists(submissionId: Long, courseExId: Long, courseId: Long): Boolean {
        return transaction {
            (Course innerJoin CourseExercise innerJoin Submission)
                .select {
                    Course.id eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (Submission.id eq submissionId)
                }.count() == 1L
        }
    }

    private fun insertTeacherAssessment(teacherId: String, submissionId: Long, assessment: Req, courseExId: Long) {
        transaction {
            TeacherAssessment.insert {
                it[submission] = EntityID(submissionId, Submission)
                it[teacher] = EntityID(teacherId, Teacher)
                it[createdAt] = DateTime.now()
                it[grade] = assessment.grade
                it[feedback] = assessment.feedback
            }
        }
        cacheService.evictSelectLatestValidGrades(courseExId)
    }

    private fun sendNotification(courseId: Long, courseExId: Long, submissionId: Long, feedback: String) {
        transaction {
            val courseTitle = Course
                .slice(Course.alias, Course.title)
                .select {
                    Course.id.eq(courseId)
                }.map {
                    it[Course.alias] ?: it[Course.title]
                }.single()

            val exerciseTitle = (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.titleAlias, ExerciseVer.title)
                .select {
                    CourseExercise.id.eq(courseExId) and ExerciseVer.validTo.isNull()
                }.map {
                    it[CourseExercise.titleAlias] ?: it[ExerciseVer.title]
                }.single()

            val studentEmail = (Submission innerJoin Student innerJoin Account)
                .slice(Account.email)
                .select {
                    Submission.id.eq(submissionId)
                }.map {
                    it[Account.email]
                }.single()

            sendMailService.sendStudentGotNewTeacherFeedback(
                courseId,
                courseExId,
                exerciseTitle,
                courseTitle,
                feedback,
                studentEmail
            )
        }
    }
}
