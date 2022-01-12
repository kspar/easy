package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherReadCoursesController {

    data class Resp(@JsonProperty("courses") val courses: List<CourseResp>)

    data class CourseResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("student_count") val studentCount: Long
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses")
    fun controller(caller: EasyUser): Resp {
        val callerId = caller.id

        val courses = if (caller.isAdmin()) {
            log.debug { "Getting courses for admin $callerId" }
            selectCoursesForAdmin()
        } else {
            log.debug { "Getting courses for teacher $callerId" }
            selectCoursesForTeacher(callerId)
        }

        return Resp(courses)
    }

    private fun selectCoursesForAdmin(): List<CourseResp> = transaction {
        val studentCount = StudentCourseAccess.student.count().alias("student_count")
        (Course leftJoin StudentCourseAccess)
            .slice(Course.id, Course.title, studentCount)
            .selectAll()
            .groupBy(Course.id, Course.title)
            .map {
                CourseResp(
                    it[Course.id].value.toString(),
                    it[Course.title],
                    it[studentCount]
                )
            }
    }

    private fun selectCoursesForTeacher(teacherId: String): List<CourseResp> {
        data class CourseAccess(val id: Long, val title: String)
        return transaction {
            // get teacher course accesses with groups
            (Course innerJoin TeacherCourseAccess leftJoin TeacherCourseGroup)
                .slice(Course.id, Course.title, TeacherCourseGroup.courseGroup)
                .select {
                    TeacherCourseAccess.teacher eq teacherId
                }
                // Group by course: (CourseAccess -> [groupIds])
                .groupBy({
                    CourseAccess(
                        it[Course.id].value,
                        it[Course.title]
                    )
                }) {
                    val courseGroupId: EntityID<Long>? = it[TeacherCourseGroup.courseGroup]
                    courseGroupId?.value
                }
                .map { (course, groupIds) ->
                    course to groupIds.filterNotNull()
                }
                .map { (course, groupIds) ->
                    // Get student count for each course
                    val studentCount = selectStudentCountForCourse(course.id, groupIds)

                    CourseResp(
                        course.id.toString(),
                        course.title,
                        studentCount
                    )
                }
        }
    }

    private fun selectStudentCountForCourse(courseId: Long, groups: List<Long>): Long {
        // Select distinct students, ignoring their groups (but respecting restricted groups in where)
        val query = (StudentCourseAccess leftJoin StudentCourseGroup)
            .slice(StudentCourseAccess.student)
            .select { StudentCourseAccess.course eq courseId }
            .withDistinct()

        if (groups.isNotEmpty()) {
            query.andWhere {
                StudentCourseGroup.courseGroup inList groups or StudentCourseGroup.courseGroup.isNull()
            }
        }

        return query.count()
    }
}