package core.ems.service.moodle

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadCourseMoodlePropsController {
    private val log = KotlinLogging.logger {}

    data class Resp(@JsonProperty("moodle_props") val moodleProps: MoodleResp?, )

    data class MoodleResp(
        @JsonProperty("moodle_short_name") val shortName: String,
        @JsonProperty("students_synced") val studentsSynced: Boolean,
        @JsonProperty("sync_students_in_progress") val studentsSyncInProgress: Boolean,
        @JsonProperty("grades_synced") val gradesSynced: Boolean,
        @JsonProperty("sync_grades_in_progress") val gradesSyncInProgress: Boolean,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/moodle")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.info { "Getting Moodle props for course $courseIdStr by ${caller.id}" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }

        return Resp(selectMoodleProps(courseId))
    }

    private fun selectMoodleProps(courseId: Long): MoodleResp? = transaction {
        Course.select {
            Course.id eq courseId
        }.map {
            val shortname = it[Course.moodleShortName]
            if (shortname != null) {
                MoodleResp(
                    shortname,
                    studentsSynced = it[Course.moodleSyncStudents],
                    studentsSyncInProgress = it[Course.moodleSyncStudentsInProgress],
                    gradesSynced = it[Course.moodleSyncGrades],
                    gradesSyncInProgress = it[Course.moodleSyncGradesInProgress]
                )
            } else null
        }.single()
    }
}