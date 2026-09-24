package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.ems.service.AiFeedbackResp
import core.ems.service.access_control.RequireStudentVisible
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.ems.service.ai.AiFeedbackService
import core.ems.service.assertSubmissionExists
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectStudentBySubmissionId
import core.exception.InvalidRequestException
import core.exception.ReqError
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * EZ-1712. "Explain what went wrong" — the student's own button, pressed on their own latest
 * failed submission. Blocking: the provider is called inside the request, like retry-autoassess,
 * and the reply is the stored explanation, which the activities endpoint will also list from now on.
 */
@RestController
@RequestMapping("/v2")
class StudentRequestAiFeedbackController(private val aiFeedbackService: AiFeedbackService) {
    private val log = KotlinLogging.logger {}

    data class Req(
        // The UI language, so the explanation matches the page around it. Core has no per-account
        // language to fall back on, which is why this is in the body rather than looked up. Not
        // validated: anything but `en` is Estonian, and a hint is not worth a 400.
        @param:JsonProperty("language") val language: String = "et",
    )

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/{submissionId}/ai-feedback")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @PathVariable("submissionId") submissionIdStr: String,
        @Valid @RequestBody(required = false) body: Req?,
        caller: EasyUser,
    ): AiFeedbackResp {

        log.info { "${caller.id} requests AI feedback for submission $submissionIdStr on course exercise $courseExIdStr on course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()
        val submissionId = submissionIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId, RequireStudentVisible(caller.id))
        assertSubmissionExists(submissionId, courseExId, courseId)

        // Not "forbidden": the same answer as for a submission that does not exist, so the endpoint
        // does not confirm that somebody else's id is real.
        if (selectStudentBySubmissionId(submissionId).value != caller.id) {
            throw InvalidRequestException(
                "No submission $submissionId found for ${caller.id}", ReqError.ENTITY_WITH_ID_NOT_FOUND
            )
        }

        val language = if (body?.language == "en") "en" else "et"
        return aiFeedbackService.explain(courseId, courseExId, submissionId, caller.id, language)
    }
}
