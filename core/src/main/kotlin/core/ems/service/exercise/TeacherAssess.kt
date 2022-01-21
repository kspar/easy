package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.cache.CachingService
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.moodle.MoodleGradesSyncService
import core.exception.InvalidRequestException
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

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherAssessController(
    val moodleGradesSyncService: MoodleGradesSyncService,
    val cachingService: CachingService
) {

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

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        if (!submissionExists(submissionId, courseExId, courseId)) {
            throw InvalidRequestException("No submission $submissionId found on course exercise $courseExId on course $courseId")
        }

        insertTeacherAssessment(callerId, submissionId, assessment, cachingService, courseExId)
        moodleGradesSyncService.syncSingleGradeToMoodle(submissionId)
    }
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

private fun insertTeacherAssessment(
    teacherId: String,
    submissionId: Long,
    assessment: TeacherAssessController.Req,
    cachingService: CachingService,
    courseExId: Long
) {
    transaction {
        TeacherAssessment.insert {
            it[submission] = EntityID(submissionId, Submission)
            it[teacher] = EntityID(teacherId, Teacher)
            it[createdAt] = DateTime.now()
            it[grade] = assessment.grade
            it[feedback] = assessment.feedback
        }
    }
    cachingService.evictSelectLatestValidGrades(courseExId)
}
