package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.SubmissionDraft
import core.ems.service.assertCourseExerciseIsOnCourse
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.http.HttpStatus
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletResponse

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentReadLatestSubmissionDraftController {

    data class Resp(@JsonProperty("solution") val solution: String,
                    @JsonSerialize(using = DateTimeSerializer::class)
                    @JsonProperty("created_at") val submissionTime: DateTime)

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/draft")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExerciseIdStr: String,
                   response: HttpServletResponse, caller: EasyUser): Resp? {

        log.debug { "Getting latest submission draft for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        val submission = selectLatestStudentSubmissionDraft(courseExId, caller.id)

        return if (submission != null) {
            submission
        } else {
            response.status = HttpStatus.NO_CONTENT.value()
            null
        }
    }
}


private fun selectLatestStudentSubmissionDraft(courseExId: Long, studentId: String):
        StudentReadLatestSubmissionDraftController.Resp? {

    return transaction {
        SubmissionDraft
                .select {
                    (SubmissionDraft.courseExercise eq courseExId) and
                            (SubmissionDraft.student eq studentId)
                }
                .map {
                    StudentReadLatestSubmissionDraftController.Resp(
                            it[SubmissionDraft.solution],
                            it[SubmissionDraft.createdAt])
                }
                .singleOrNull()
    }
}


