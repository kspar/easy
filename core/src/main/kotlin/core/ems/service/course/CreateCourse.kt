package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import mu.KotlinLogging
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size


@RestController
@RequestMapping("/v2")
class CreateCourse {
    private val log = KotlinLogging.logger {}

    data class Req(
        @JsonProperty("title") @field:NotBlank @field:Size(max = 100) val title: String
    )

    data class Resp(@JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/admin/courses")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.info { "Create course '${dto.title}' by ${caller.id}" }
        val courseId = insertCourse(dto.title)
        return Resp(courseId.toString())
    }

    private fun insertCourse(courseTitle: String): Long = transaction {
        Course.insertAndGetId {
            it[createdAt] = DateTime.now()
            it[title] = courseTitle
        }
    }.value
}
