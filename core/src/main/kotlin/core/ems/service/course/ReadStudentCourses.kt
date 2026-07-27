package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.Course
import core.db.StudentCourseAccess
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
        @get:JsonProperty("courses") val courses: List<CourseResp>
    )

    data class CourseResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("alias") val alias: String?,
        @get:JsonProperty("course_code") val courseCode: String?,
        @get:JsonProperty("archived") val archived: Boolean,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("last_accessed") val lastAccessed: DateTime,
        @get:JsonProperty("color") val color: String,
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
                Course.id,
                Course.title,
                Course.alias,
                Course.courseCode,
                Course.archived,
                Course.color,
                StudentCourseAccess.lastAccessed
            ).where {
                StudentCourseAccess.student eq studentId
            }.map {
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[Course.alias],
                    it[Course.courseCode],
                    it[Course.archived],
                    it[StudentCourseAccess.lastAccessed],
                    it[Course.color],
                )
            }
        )
    }
}