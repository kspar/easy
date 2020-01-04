package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadParticipantsOnCourseController {

    data class GroupResp(@JsonProperty("id") val id: String,
                         @JsonProperty("name") val name: String)

    data class StudentsResp(@JsonProperty("id") val id: String,
                            @JsonProperty("email") val email: String,
                            @JsonProperty("given_name") val givenName: String,
                            @JsonProperty("family_name") val familyName: String,
                            @JsonProperty("groups") val groups: List<GroupResp>)

    data class TeachersResp(@JsonProperty("id") val id: String,
                            @JsonProperty("email") val email: String,
                            @JsonProperty("given_name") val givenName: String,
                            @JsonProperty("family_name") val familyName: String,
                            @JsonProperty("groups") val groups: List<GroupResp>)

    data class StudentPendingResp(@JsonProperty("email") val email: String,
                                  @JsonSerialize(using = DateTimeSerializer::class)
                                  @JsonProperty("valid_from") val validFrom: DateTime,
                                  @JsonProperty("groups") val groups: List<GroupResp>)

    data class StudentMoodlePendingResp(@JsonProperty("ut_username") val utUsername: String,
                                        @JsonProperty("groups") val groups: List<GroupResp>)


    data class Resp(@JsonProperty("moodle_short_name")
                    @JsonInclude(Include.NON_EMPTY)
                    val moodleShortName: String?,
                    @JsonProperty("students")
                    @JsonInclude(Include.NON_EMPTY)
                    val students: List<StudentsResp>?,
                    @JsonProperty("teachers")
                    @JsonInclude(Include.NON_EMPTY)
                    val teachers: List<TeachersResp>?,
                    @JsonProperty("students_pending")
                    @JsonInclude(Include.NON_EMPTY)
                    val studentPendingAccess: List<StudentPendingResp>?,
                    @JsonProperty("students_moodle_pending")
                    @JsonInclude(Include.NON_EMPTY)
                    val studentMoodlePendingAccess: List<StudentMoodlePendingResp>?)


    enum class Role(val paramValue: String) {
        TEACHER("teacher"),
        STUDENT("student"),
        ALL("all")
    }

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/courses/{courseId}/participants")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestParam("role", required = false) roleReq: String?,
                   caller: EasyUser): Resp {

        log.debug { "Getting participants on course $courseIdStr for ${caller.id} (role: $roleReq)" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        // TODO: restrict to my groups

        val shortName = selectMoodleShortName(courseId)

        when (roleReq) {
            Role.TEACHER.paramValue -> {
                val teachers = selectTeachersOnCourse(courseId)
                return Resp(shortName, null, teachers, null, null)
            }
            Role.STUDENT.paramValue -> {
                val students = selectStudentsOnCourse(courseId)
                val studentsPending = selectStudentsPendingOnCourse(courseId)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId)
                return Resp(shortName, students, null, studentsPending, studentsMoodle)
            }
            Role.ALL.paramValue, null -> {
                val students = selectStudentsOnCourse(courseId)
                val teachers = selectTeachersOnCourse(courseId)
                val studentsPending = selectStudentsPendingOnCourse(courseId)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId)
                return Resp(shortName, students, teachers, studentsPending, studentsMoodle)
            }
            else -> throw InvalidRequestException("Invalid parameter $roleReq")
        }
    }
}


private fun selectStudentsOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.StudentsResp> {
    data class StudentOnCourse(val id: String, val email: String, val givenName: String, val familyName: String)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentGroupAccess leftJoin Group)
                .slice(Account.id, Account.email, Account.givenName, Account.familyName, Group.id, Group.name)
                .select { StudentCourseAccess.course eq courseId }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            StudentOnCourse(
                                    it[Account.id].value,
                                    it[Account.email],
                                    it[Account.givenName],
                                    it[Account.familyName]
                            ),
                            if (groupId == null) null else
                                StudentGroup(
                                        groupId.value.toString(),
                                        it[Group.name]
                                )
                    )
                }
                .groupBy({ it.first }) { it.second }
                .map { (student, groups) ->
                    ReadParticipantsOnCourseController.StudentsResp(
                            student.id,
                            student.email,
                            student.givenName,
                            student.familyName,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            }
                    )
                }
    }
}

private fun selectStudentsPendingOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.StudentPendingResp> {
    data class PendingStudent(val email: String, val validFrom: DateTime)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        (StudentPendingAccess leftJoin StudentPendingGroup leftJoin Group)
                .slice(StudentPendingAccess.email, StudentPendingAccess.validFrom, Group.id, Group.name)
                .select { StudentPendingAccess.course eq courseId }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            PendingStudent(
                                    it[StudentPendingAccess.email],
                                    it[StudentPendingAccess.validFrom]
                            ),
                            if (groupId == null) null else
                                StudentGroup(
                                        groupId.value.toString(),
                                        it[Group.name]
                                )
                    )
                }
                .groupBy({ it.first }) { it.second }
                .map { (student, groups) ->
                    ReadParticipantsOnCourseController.StudentPendingResp(
                            student.email,
                            student.validFrom,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            }
                    )
                }
    }
}

private fun selectMoodleStudentsPendingOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.StudentMoodlePendingResp> {
    data class PendingStudent(val moodleUsername: String)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        (StudentMoodlePendingAccess leftJoin StudentMoodlePendingGroup leftJoin Group)
                .slice(StudentMoodlePendingAccess.moodleUsername, Group.id, Group.name)
                .select { StudentMoodlePendingAccess.course eq courseId }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            PendingStudent(
                                    it[StudentMoodlePendingAccess.moodleUsername]
                            ),
                            if (groupId == null) null else
                                StudentGroup(
                                        groupId.value.toString(),
                                        it[Group.name]
                                )
                    )
                }
                .groupBy({ it.first }) { it.second }
                .map { (student, groups) ->
                    ReadParticipantsOnCourseController.StudentMoodlePendingResp(
                            student.moodleUsername,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            }
                    )
                }
    }
}

private fun selectTeachersOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.TeachersResp> {
    data class TeacherOnCourse(val id: String, val email: String, val givenName: String, val familyName: String)
    data class TeacherGroup(val id: String, val name: String)

    return transaction {
        (Account innerJoin Teacher innerJoin TeacherCourseAccess leftJoin TeacherGroupAccess leftJoin Group)
                .slice(Account.id, Account.email, Account.givenName, Account.familyName, Group.id, Group.name)
                .select { TeacherCourseAccess.course eq courseId }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            TeacherOnCourse(
                                    it[Account.id].value,
                                    it[Account.email],
                                    it[Account.givenName],
                                    it[Account.familyName]
                            ),
                            if (groupId == null) null else
                                TeacherGroup(
                                        groupId.value.toString(),
                                        it[Group.name]
                                )
                    )
                }
                .groupBy({ it.first }) { it.second }
                .map { (teacher, groups) ->
                    ReadParticipantsOnCourseController.TeachersResp(
                            teacher.id,
                            teacher.email,
                            teacher.givenName,
                            teacher.familyName,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            }
                    )
                }
    }
}

private fun selectMoodleShortName(courseId: Long): String? {
    return transaction {
        Course.slice(Course.moodleShortName)
                .select { Course.id eq courseId }
                .map { it[Course.moodleShortName] }
                .singleOrNull()
    }
}
