package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.getTeacherRestrictedCourseGroups
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadParticipantsOnCourseController {

    data class GroupResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("name") val name: String
    )

    data class StudentsResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("email") val email: String,
        @JsonProperty("given_name") val givenName: String,
        @JsonProperty("family_name") val familyName: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime?,
        @JsonProperty("groups") val groups: List<GroupResp>,
        @JsonProperty("moodle_username") val moodleUsername: String?,
    )

    data class TeachersResp(
        @JsonProperty("id") val id: String,
        @JsonProperty("email") val email: String,
        @JsonProperty("given_name") val givenName: String,
        @JsonProperty("family_name") val familyName: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("created_at") val createdAt: DateTime?,
        @JsonProperty("groups") val groups: List<GroupResp>
    )

    data class StudentPendingResp(
        @JsonProperty("email") val email: String,
        @JsonSerialize(using = DateTimeSerializer::class)
        @JsonProperty("valid_from") val validFrom: DateTime?,
        @JsonProperty("groups") val groups: List<GroupResp>
    )

    data class StudentMoodlePendingResp(
        @JsonProperty("moodle_username") val moodleUsername: String,
        @JsonProperty("email") val email: String,
        @JsonProperty("groups") val groups: List<GroupResp>
    )

    data class Resp(
        @JsonProperty("students") @JsonInclude(Include.NON_NULL) val students: List<StudentsResp>?,
        @JsonProperty("teachers") @JsonInclude(Include.NON_NULL) val teachers: List<TeachersResp>?,
        @JsonProperty("students_pending") @JsonInclude(Include.NON_NULL) val studentPendingAccess: List<StudentPendingResp>?,
        @JsonProperty("students_moodle_pending") @JsonInclude(Include.NON_NULL) val studentMoodlePendingAccess: List<StudentMoodlePendingResp>?
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
        @RequestParam("role", required = false) roleReq: String?,
        caller: EasyUser
    ): Resp {

        log.debug { "Getting participants on course $courseIdStr for ${caller.id} (role: $roleReq)" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val restrictedGroups = getTeacherRestrictedCourseGroups(courseId, caller)

        return when (roleReq?.lowercase()) {
            Role.TEACHER.paramValue -> {
                val teachers = selectTeachersOnCourse(courseId)
                Resp(
                    null,
                    teachers,
                    null,
                    null,
                )
            }
            Role.STUDENT.paramValue -> {
                val students = selectStudentsOnCourse(courseId, restrictedGroups)
                val studentsPending = selectStudentsPendingOnCourse(courseId, restrictedGroups)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId, restrictedGroups)
                Resp(
                    students,
                    null,
                    studentsPending,
                    studentsMoodle
                )
            }
            Role.ALL.paramValue, null -> {
                val students = selectStudentsOnCourse(courseId, restrictedGroups)
                val teachers = selectTeachersOnCourse(courseId)
                val studentsPending = selectStudentsPendingOnCourse(courseId, restrictedGroups)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId, restrictedGroups)
                Resp(
                    students,
                    teachers,
                    studentsPending,
                    studentsMoodle
                )
            }
            else -> throw InvalidRequestException("Invalid parameter value role=$roleReq")
        }
    }


    data class ParticipantGroup(val id: Long, val name: String)

    private fun selectStudentsOnCourse(courseId: Long, restrictedGroups: List<Long>): List<StudentsResp> {
        data class StudentOnCourse(
            val id: String,
            val email: String,
            val givenName: String,
            val familyName: String,
            val createdAt: DateTime?,
            val moodleUsername: String?
        )

        return transaction {
            (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentCourseGroup leftJoin CourseGroup)
                .slice(
                    Account.id, Account.email, Account.givenName, Account.familyName, Account.moodleUsername,
                    StudentCourseAccess.createdAt, CourseGroup.id, CourseGroup.name
                ).select {
                    StudentCourseAccess.course.eq(courseId)
                }.groupBy({
                    StudentOnCourse(
                        it[Account.id].value,
                        it[Account.email],
                        it[Account.givenName],
                        it[Account.familyName],
                        it[StudentCourseAccess.createdAt],
                        it[Account.moodleUsername]
                    )
                }) {
                    val groupId: EntityID<Long>? = it[CourseGroup.id]
                    if (groupId != null) ParticipantGroup(groupId.value, it[CourseGroup.name]) else null
                }
                .map { (student, groups) ->
                    student to groups.filterNotNull()
                }
                .filter { (student, groups) ->
                    restrictedGroups.isEmpty() || groups.isEmpty() || groups.any { restrictedGroups.contains(it.id) }
                }
                .map { (student, groups) ->
                    StudentsResp(
                        student.id,
                        student.email,
                        student.givenName,
                        student.familyName,
                        student.createdAt,
                        groups.map {
                            GroupResp(it.id.toString(), it.name)
                        },
                        student.moodleUsername
                    )
                }
        }
    }

    private fun selectStudentsPendingOnCourse(courseId: Long, restrictedGroups: List<Long>): List<StudentPendingResp> {
        data class PendingStudent(val email: String, val validFrom: DateTime)

        return transaction {
            (StudentPendingAccess leftJoin StudentPendingCourseGroup leftJoin CourseGroup)
                .slice(
                    StudentPendingAccess.email,
                    StudentPendingAccess.validFrom,
                    CourseGroup.id,
                    CourseGroup.name
                ).select {
                    StudentPendingAccess.course eq courseId
                }.groupBy({
                    PendingStudent(
                        it[StudentPendingAccess.email],
                        it[StudentPendingAccess.validFrom]
                    )
                }) {
                    val groupId: EntityID<Long>? = it[CourseGroup.id]
                    if (groupId != null) ParticipantGroup(groupId.value, it[CourseGroup.name]) else null
                }
                .map { (student, groups) ->
                    student to groups.filterNotNull()
                }
                .filter { (student, groups) ->
                    restrictedGroups.isEmpty() || groups.isEmpty() || groups.any { restrictedGroups.contains(it.id) }
                }
                .map { (student, groups) ->
                    StudentPendingResp(
                        student.email,
                        student.validFrom,
                        groups.map {
                            GroupResp(it.id.toString(), it.name)
                        }
                    )
                }
        }
    }

    private fun selectMoodleStudentsPendingOnCourse(
        courseId: Long,
        restrictedGroups: List<Long>
    ): List<StudentMoodlePendingResp> {
        data class PendingStudent(val moodleUsername: String, val email: String)

        return transaction {
            (StudentMoodlePendingAccess leftJoin StudentMoodlePendingCourseGroup leftJoin CourseGroup)
                .slice(
                    StudentMoodlePendingAccess.moodleUsername,
                    StudentMoodlePendingAccess.email,
                    CourseGroup.id,
                    CourseGroup.name
                ).select {
                    StudentMoodlePendingAccess.course eq courseId
                }.groupBy({
                    PendingStudent(
                        it[StudentMoodlePendingAccess.moodleUsername],
                        it[StudentMoodlePendingAccess.email]
                    )
                }) {
                    val groupId: EntityID<Long>? = it[CourseGroup.id]
                    if (groupId != null) ParticipantGroup(groupId.value, it[CourseGroup.name]) else null
                }
                .map { (student, groups) ->
                    student to groups.filterNotNull()
                }
                .filter { (student, groups) ->
                    restrictedGroups.isEmpty() || groups.isEmpty() || groups.any { restrictedGroups.contains(it.id) }
                }
                .map { (student, groups) ->
                    StudentMoodlePendingResp(
                        student.moodleUsername,
                        student.email,
                        groups.map {
                            GroupResp(it.id.toString(), it.name)
                        }
                    )
                }
        }
    }

    private fun selectTeachersOnCourse(courseId: Long): List<TeachersResp> {
        data class TeacherOnCourse(
            val id: String,
            val email: String,
            val givenName: String,
            val familyName: String,
            val createdAt: DateTime?
        )

        return transaction {
            (Account innerJoin Teacher innerJoin TeacherCourseAccess leftJoin TeacherCourseGroup leftJoin CourseGroup)
                .slice(
                    Account.id, Account.email, Account.givenName, Account.familyName,
                    CourseGroup.id, CourseGroup.name, TeacherCourseAccess.createdAt
                ).select {
                    TeacherCourseAccess.course eq courseId
                }.groupBy({
                    TeacherOnCourse(
                        it[Account.id].value,
                        it[Account.email],
                        it[Account.givenName],
                        it[Account.familyName],
                        it[TeacherCourseAccess.createdAt]
                    )
                }) {
                    val groupId: EntityID<Long>? = it[CourseGroup.id]
                    if (groupId != null) ParticipantGroup(groupId.value, it[CourseGroup.name]) else null
                }
                .map { (teacher, groups) ->
                    TeachersResp(
                        teacher.id,
                        teacher.email,
                        teacher.givenName,
                        teacher.familyName,
                        teacher.createdAt,
                        groups.filterNotNull().map {
                            GroupResp(it.id.toString(), it.name)
                        }
                    )
                }
        }
    }
}
