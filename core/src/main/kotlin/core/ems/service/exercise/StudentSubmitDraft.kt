package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.SubmissionDraft
import core.ems.service.access_control.RequireStudentVisible
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class StudentSubmitDraftController {
    private val log = KotlinLogging.logger {}

    data class Req(@param:JsonProperty("solution", required = true) @field:Size(max = 300000) val solution: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/draft")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody solutionBody: Req, caller: EasyUser
    ) {

        log.info { "Creating new submission draft by ${caller.id} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId, RequireStudentVisible(caller.id))

        insertOrUpdateSubmissionDraft(courseExId, solutionBody.solution, caller.id)
    }

    private fun insertOrUpdateSubmissionDraft(courseExId: Long, submission: String, studentId: String) = transaction {
        SubmissionDraft.upsert(
            SubmissionDraft.courseExercise,
            SubmissionDraft.student,
            onUpdateExclude = listOf(
                SubmissionDraft.courseExercise,
                SubmissionDraft.student
            )
        ) {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[student] = studentId
            it[createdAt] = DateTime.now()
            it[solution] = submission
        }
    }
}
