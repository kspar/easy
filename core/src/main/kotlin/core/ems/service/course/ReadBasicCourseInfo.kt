package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.userOnCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadBasicCourseInfo {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("title") val title: String,
        @JsonProperty("alias") val alias: String?,
        @JsonProperty("archived") val archived: Boolean,
        @JsonProperty("color") val color: String,
        @JsonProperty("course_code") val courseCode: String?,
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
        Course.select(Course.title, Course.alias, Course.archived, Course.color, Course.courseCode)
            .where { Course.id eq courseId }
            .map {
                Resp(
                    it[Course.title],
                    it[Course.alias],
                    it[Course.archived],
                    it[Course.color],
                    it[Course.courseCode],
                )
            }
            .single()
    }
}
