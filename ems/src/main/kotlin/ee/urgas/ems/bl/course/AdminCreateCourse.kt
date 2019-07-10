package ee.urgas.ems.bl.course

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.Course
import mu.KotlinLogging
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AdminCreateCourseController {

    data class NewCourseBody(@JsonProperty("title", required = true) val title: String)

    data class CreatedCourseResponse(@JsonProperty("id") val id: String)


    @Secured("ROLE_ADMIN")
    @PostMapping("/admin/courses")
    fun createCourse(@RequestBody dto: NewCourseBody, caller: EasyUser): CreatedCourseResponse {
        log.debug { "Create course '${dto.title}' by ${caller.id}" }
        val courseId = insertCourse(dto)
        return CreatedCourseResponse(courseId.toString())
    }
}


private fun insertCourse(body: AdminCreateCourseController.NewCourseBody): Long {
    return transaction {
        Course.insertAndGetId {
            it[createdAt] = DateTime.now()
            it[title] = body.title
        }
    }.value

}
