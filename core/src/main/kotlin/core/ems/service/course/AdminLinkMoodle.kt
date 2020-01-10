package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminLinkMoodleCourseController(val moodleSyncService: MoodleSyncService) {


    data class Req(@JsonProperty("moodle_short_name", required = true) @field:NotBlank @field:Size(max = 500)
                   val moodleShortName: String)

    data class Resp(@JsonProperty("students_synced") val studentsSynced: Int,
                    @JsonProperty("pending_students_synced") val pendingStudentsSynced: Int)


    @Secured("ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodle")
    fun controller(@PathVariable("courseId") courseIdStr: String, @Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.debug { "${caller.id} is one-way linking Moodle course '${dto.moodleShortName}' with '$courseIdStr'" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        checkIfCourseExists(courseId)
        assertTeacherOrAdminHasAccessToCourse(caller, courseId)
        // TODO: add Course.moodle_sync_students: Boolean
        val students = moodleSyncService.queryStudents(dto.moodleShortName)
        val syncedStudents = moodleSyncService.syncCourse(students, courseId, dto.moodleShortName)
        return Resp(syncedStudents.syncedStudents, syncedStudents.syncedPendingStudents)
    }
}


private fun checkIfCourseExists(courseId: Long) {
    transaction {
        if (Course.select { Course.id eq courseId }.count() != 1) {
            throw InvalidRequestException("Course with $courseId does not exist.")
        }
    }
}