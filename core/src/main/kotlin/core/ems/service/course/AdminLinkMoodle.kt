package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminLinkMoodleCourseController {

    data class Req(@JsonProperty("moodle_course_id", required = true) @field:NotBlank @field:Size(max = 200)
                   val moodleCourseId: String)


    @Secured("ROLE_ADMIN")
    @PostMapping("/admin/courses/{courseId}/moodle")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser, @PathVariable courseId: String) {
        log.debug { "${caller.id} is linking Moodle course '${dto.moodleCourseId}' with '$courseId'" }
    }
}