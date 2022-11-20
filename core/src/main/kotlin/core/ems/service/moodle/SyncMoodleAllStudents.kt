package core.ems.service.moodle

import core.conf.security.EasyUser
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.ResourceLockedException
import mu.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class MoodleAllStudentsSyncController(val moodleStudentsSyncService: MoodleStudentsSyncService) {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodle/students")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        caller: EasyUser
    ): MoodleSyncedOperationResponse {

        log.debug { "Syncing students on course $courseIdStr with Moodle by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId, true) }
        assertCourseIsMoodleLinked(courseId)

        return try {
            moodleStudentsSyncService.syncStudents(courseId)
            MoodleSyncedOperationResponse(MoodleSyncStatus.FINISHED)
        } catch (e: ResourceLockedException) {
            log.info { "Moodle sync students already in progress for course $courseId" }
            MoodleSyncedOperationResponse(MoodleSyncStatus.IN_PROGRESS)
        }
    }
}
