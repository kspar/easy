package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.ems.service.assertUserHasAccessToCourse
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
class ReadBasicCourseInfo {

    data class Resp(@JsonProperty("title") val title: String)

    @Secured("ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/basic")
    fun controller(@PathVariable("courseId") courseIdStr: String, caller: EasyUser): Resp {

        log.debug { "Getting basic course info for ${caller.id} for course $courseIdStr" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertUserHasAccessToCourse(caller, courseId)

        val courseInfo = selectCourseInfo(courseId)
        log.debug { "Got course info: $courseInfo" }
        return courseInfo
    }
}

private fun selectCourseInfo(courseId: Long): ReadBasicCourseInfo.Resp =
        transaction {
            Course.slice(Course.title)
                    .select {
                        Course.id eq courseId
                    }
                    .map {
                        ReadBasicCourseInfo.Resp(
                                it[Course.title]
                        )
                    }
                    .single()
        }


