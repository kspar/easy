package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.Student
import core.db.SubmissionDraft
import core.db.insertOrUpdate
import core.ems.service.assertCourseExerciseIsOnCourse
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentSubmitDraftController {

    data class Req(@JsonProperty("solution", required = true) @field:Size(max = 300000) val solution: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/draft")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExIdStr: String,
                   @Valid @RequestBody solutionBody: Req, caller: EasyUser) {

        log.debug { "Creating new submission draft by ${caller.id} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        insertOrUpdateSubmissionDraft(courseExId, solutionBody.solution, caller.id)
    }
}


private fun insertOrUpdateSubmissionDraft(courseExId: Long, submission: String, studentId: String) {
    return transaction {
        SubmissionDraft.insertOrUpdate(listOf(SubmissionDraft.courseExercise, SubmissionDraft.student),
                listOf(SubmissionDraft.courseExercise, SubmissionDraft.student)) {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[student] = EntityID(studentId, Student)
            it[createdAt] = DateTime.now()
            it[solution] = submission
        }
    }
}


