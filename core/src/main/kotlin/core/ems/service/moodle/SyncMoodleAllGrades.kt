package core.ems.service.moodle

import core.conf.security.EasyUser
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.ResourceLockedException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class MoodleAllGradesSyncController(val moodleGradesSyncService: MoodleGradesSyncService) {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/moodle/grades")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        caller: EasyUser
    ): MoodleSyncedOperationResponse {

        log.info { "Syncing all grades for course $courseIdStr with Moodle by ${caller.id}" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseIsMoodleLinked(courseId, requireGradesSynced = true)

        return try {
            moodleGradesSyncService.syncCourseGradesToMoodle(courseId)
            MoodleSyncedOperationResponse(MoodleSyncStatus.FINISHED)
        } catch (_: ResourceLockedException) {
            log.info { "Moodle sync grades already in progress for course $courseId" }
            MoodleSyncedOperationResponse(MoodleSyncStatus.IN_PROGRESS)
        }
    }
}
