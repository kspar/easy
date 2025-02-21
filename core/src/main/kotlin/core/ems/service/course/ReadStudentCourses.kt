package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentCourseAccess
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadStudentCourses {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @JsonProperty("courses") val courses: List<CourseResp>
    )

    data class CourseResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("alias") val alias: String?,
        @JsonProperty("archived") val archived: Boolean,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("last_accessed") val lastAccessed: DateTime,
    )

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses")
    fun controller(caller: EasyUser): Resp {
        val callerId = caller.id
        log.info { "Getting courses for student $callerId" }
        return selectCoursesForStudent(callerId)
    }

    private fun selectCoursesForStudent(studentId: String): Resp = transaction {
        Resp(
            (StudentCourseAccess innerJoin Course).select(
                Course.id, Course.title, Course.alias, Course.archived, StudentCourseAccess.lastAccessed
            ).where {
                StudentCourseAccess.student eq studentId
            }.map {
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    it[Course.archived],
                    it[StudentCourseAccess.lastAccessed],
                )
            }
        )
    }
}
