package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.access.canUserAccessCourse
import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import ee.urgas.ems.exception.ForbiddenException
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
class ReadBasicCourseInfo {

    data class BasicCourseResponse(@JsonProperty("title") val title: String)

    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/basic")
    fun getCourseBasic(@PathVariable("courseId") courseIdStr: String,
                       caller: EasyUser): BasicCourseResponse {

        log.debug { "Getting basic course info for ${caller.id} for course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        if (!canUserAccessCourse(caller, courseId)) {
            throw ForbiddenException("User ${caller.id} does not have access to course $courseId")
        }

        val courseInfo = selectCourseInfo(courseId)
        log.debug { "Got course info: $courseInfo" }
        return courseInfo
    }

    private fun selectCourseInfo(courseId: Long): BasicCourseResponse =
            transaction {
                Course.slice(Course.title)
                        .select {
                            Course.id eq courseId
                        }
                        .map {
                            BasicCourseResponse(
                                    it[Course.title]
                            )
                        }
                        .single()
            }
}


