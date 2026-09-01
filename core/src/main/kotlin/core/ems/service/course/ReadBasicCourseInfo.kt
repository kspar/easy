package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.userOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.moodle.MoodleCourseUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadBasicCourseInfo(
    private val moodleCourseUrl: MoodleCourseUrl,
) {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("alias") val alias: String?,
        @get:JsonProperty("archived") val archived: Boolean,
        @get:JsonProperty("color") val color: String,
        @get:JsonProperty("course_code") val courseCode: String?,
        /**
         * The course's page in Moodle, or null when there is nothing to link to — the course is not
         * Moodle-linked, or this environment has no Moodle configured (EZ-1874).
         *
         * A finished URL rather than the shortname, so that no caller builds one or encodes
         * anything, and so an environment with no Moodle sends a null that reads directly as "show
         * no link".
         *
         * Here, on the course-identity endpoint, and not on `GET /courses/{courseId}/moodle`, which
         * is otherwise where everything Moodle lives:
         *
         * - This endpoint is `userOnCourse`, which is exactly the audience. Students on a linked
         *   course move between the two systems as much as their teachers do and are enrolled in
         *   that Moodle course anyway; the Moodle props endpoint is `teacherOnCourse` and could
         *   never serve them.
         * - The sidebar already fetches this for the course title, so the link costs no request.
         * - Those props are **polled every three seconds while a sync runs** (EZ-1768). That cadence
         *   belongs to a sync-status resource, not to the cached course-identity one.
         *
         * What it deliberately is not is a role-dependent response: one URL answering with different
         * fields per caller makes every field optional on the client, and the frontend switches
         * active role without a reload, so a cached response and the caller's role can disagree.
         */
        @get:JsonProperty("moodle_course_url") val moodleCourseUrl: String?,
    )

    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/basic")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.info { "Getting basic course info for ${caller.id} for course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { userOnCourse(courseId) }

        return selectCourseInfo(courseId)
    }

    private fun selectCourseInfo(courseId: Long): Resp = transaction {
        Course.select(
            Course.title, Course.alias, Course.archived, Course.color, Course.courseCode, Course.moodleShortName
        )
            .where { Course.id eq courseId }
            .map {
                Resp(
                    it[Course.title],
                    it[Course.alias],
                    it[Course.archived],
                    it[Course.color],
                    it[Course.courseCode],
                    moodleCourseUrl.urlFor(it[Course.moodleShortName]),
                )
            }
            .single()
    }
}
