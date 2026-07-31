package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class CreateCourse {
    private val log = KotlinLogging.logger {}

    data class Req(
        @param:JsonProperty("title") @field:NotBlank @field:Size(max = 100) val title: String,
        @param:JsonProperty("color") @field:NotBlank @field:Size(max = 20) val color: String,
        @param:JsonProperty("course_code") @field:Size(max = 100) val courseCode: String?,
    )

    data class Resp(@get:JsonProperty("id") val id: String)

    @Secured("ROLE_ADMIN")
    @PostMapping("/admin/courses")
    fun controller(@Valid @RequestBody dto: Req, caller: EasyUser): Resp {
        log.info { "Create course '${dto.title}' by ${caller.id}" }
        val courseId = insertCourse(dto)
        return Resp(courseId.toString())
    }

    private fun insertCourse(dto: Req): Long = transaction {
        Course.insertAndGetId {
            it[createdAt] = DateTime.now()
            it[title] = dto.title
            it[color] = dto.color
            it[courseCode] = dto.courseCode
        }
    }.value
}