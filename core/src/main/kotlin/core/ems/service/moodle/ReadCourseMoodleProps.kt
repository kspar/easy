package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadCourseMoodlePropsController {

    data class Resp(
        @JsonProperty("moodle_props") val moodleProps: MoodleResp?,
    )

    data class MoodleResp(
        @JsonProperty("moodle_short_name") val moodleShortName: String,
        @JsonProperty("moodle_students_synced") val moodleStudentsSynced: Boolean,
        @JsonProperty("moodle_sync_students_in_progress") val moodleStudentsSyncInProgress: Boolean,
        @JsonProperty("moodle_grades_synced") val moodleGradesSynced: Boolean,
        @JsonProperty("moodle_sync_grades_in_progress") val moodleGradesSyncInProgress: Boolean,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/moodle")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        caller: EasyUser
    ): Resp {

        log.debug { "Getting Moodle props for course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return Resp(selectMoodleProps(courseId))
    }

    private fun selectMoodleProps(courseId: Long): MoodleResp? {
        return transaction {
            Course.select {
                Course.id eq courseId
            }.map {
                val shortname = it[Course.moodleShortName]
                if (shortname != null) {
                    MoodleResp(
                        shortname,
                        moodleStudentsSynced = it[Course.moodleSyncStudents],
                        moodleStudentsSyncInProgress = it[Course.moodleSyncStudentsInProgress],
                        moodleGradesSynced = it[Course.moodleSyncGrades],
                        moodleGradesSyncInProgress = it[Course.moodleSyncGradesInProgress]
                    )
                } else null
            }.single()
        }
    }
}