package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.GroupResp
import core.ems.service.StudentsResp
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.ems.service.selectStudentsOnCourse
import core.exception.InvalidRequestException
import core.util.DateTimeSerializer
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/v2")
class ReadParticipantsOnCourseController {
    private val log = KotlinLogging.logger {}

    data class TeachersResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("email") val email: String,
        @get:JsonProperty("given_name") val givenName: String,
        @get:JsonProperty("family_name") val familyName: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime?
    )

    data class StudentMoodlePendingResp(
        @get:JsonProperty("moodle_username") val moodleUsername: String,
        @get:JsonProperty("email") val email: String,
        @get:JsonProperty("invite_id") val inviteId: String,
        @get:JsonProperty("groups") val groups: List<GroupResp>
    )

    data class Resp(
        @get:JsonProperty("students") @get:JsonInclude(Include.NON_NULL) val students: List<StudentsResp>?,
        @get:JsonProperty("teachers") @get:JsonInclude(Include.NON_NULL) val teachers: List<TeachersResp>?,
        @get:JsonProperty("students_moodle_pending") @get:JsonInclude(Include.NON_NULL) val studentMoodlePendingAccess: List<StudentMoodlePendingResp>?,
        @get:JsonProperty("moodle_linked") val moodleLinked: Boolean
    )

    enum class Role(val paramValue: String) {
        TEACHER("teacher"),
        STUDENT("student"),
        ALL("all")
    }

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/participants")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @RequestParam("group", required = false) groupIdString: String?,
        @RequestParam("role", required = false) roleReq: String?,
        caller: EasyUser
    ): Resp {

        log.info { "Getting participants on course $courseIdStr for ${caller.id} (role: $roleReq)" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val groupId = groupIdString?.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }

        val moodleLinked = isMoodleLinked(courseId)

        return when (roleReq?.lowercase()) {
            Role.TEACHER.paramValue -> {
                val teachers = selectTeachersOnCourse(courseId)
                Resp(
                    null,
                    teachers,
                    null,
                    moodleLinked,
                )
            }

            Role.STUDENT.paramValue -> {
                val students = selectStudentsOnCourse(courseId, groupId)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId, groupId)
                Resp(
                    students,
                    null,
                    studentsMoodle,
                    moodleLinked,
                )
            }

            Role.ALL.paramValue, null -> {
                val students = selectStudentsOnCourse(courseId, groupId)
                val teachers = selectTeachersOnCourse(courseId)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId, groupId)
                Resp(
                    students,
                    teachers,
                    studentsMoodle,
                    moodleLinked,
                )
            }

            else -> throw InvalidRequestException("Invalid parameter value role=$roleReq")
        }
    }


    private fun isMoodleLinked(courseId: Long): Boolean = transaction {
        Course.select(Course.moodleShortName)
            .where { Course.id eq courseId }
            .single()[Course.moodleShortName] != null
    }

    data class ParticipantGroup(val id: Long, val name: String)

    private fun selectMoodleStudentsPendingOnCourse(courseId: Long, groupId: Long?): List<StudentMoodlePendingResp> =
        transaction {
            data class PendingStudent(val moodleUsername: String, val email: String, val inviteId: String)

            (StudentMoodlePendingAccess leftJoin StudentMoodlePendingCourseGroup leftJoin CourseGroup)
                .select(
                    StudentMoodlePendingAccess.moodleUsername,
                    StudentMoodlePendingAccess.email,
                    StudentMoodlePendingAccess.inviteId,
                    CourseGroup.id,
                    CourseGroup.name
                ).where {
                    StudentMoodlePendingAccess.course eq courseId
                }.also {
                    if (groupId != null) it.andWhere { CourseGroup.id eq groupId }
                }.groupBy({
                    PendingStudent(
                        it[StudentMoodlePendingAccess.moodleUsername],
                        it[StudentMoodlePendingAccess.email],
                        it[StudentMoodlePendingAccess.inviteId],
                    )
                }) {
                    val groupId: EntityID<Long>? = it[CourseGroup.id]
                    if (groupId != null) ParticipantGroup(groupId.value, it[CourseGroup.name]) else null
                }
                .map { (student, groups) ->
                    student to groups.filterNotNull()
                }
                .map { (student, groups) ->
                    StudentMoodlePendingResp(
                        student.moodleUsername,
                        student.email,
                        student.inviteId,
                        groups.map {
                            GroupResp(it.id.toString(), it.name)
                        }
                    )
                }
        }

    private fun selectTeachersOnCourse(courseId: Long): List<TeachersResp> = transaction {
        (Account innerJoin TeacherCourseAccess)
            .select(
                Account.id,
                Account.email,
                Account.givenName,
                Account.familyName,
                TeacherCourseAccess.createdAt
            ).where {
                TeacherCourseAccess.course eq courseId
            }
            .map {
                TeachersResp(
                    it[Account.id].value,
                    it[Account.email],
                    it[Account.givenName],
                    it[Account.familyName],
                    it[TeacherCourseAccess.createdAt]
                )
            }
    }
}
