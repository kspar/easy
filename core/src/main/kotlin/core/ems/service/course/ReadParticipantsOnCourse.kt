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

    data class GroupResp(@JsonProperty("id") val id: String,
                         @JsonProperty("name") val name: String)

    data class StudentsResp(@JsonProperty("id") val id: String,
                            @JsonProperty("email") val email: String,
                            @JsonProperty("given_name") val givenName: String,
                            @JsonProperty("family_name") val familyName: String,
                            @JsonSerialize(using = DateTimeSerializer::class)
                            @JsonProperty("created_at") val createdAt: DateTime,
                            @JsonProperty("groups") val groups: List<GroupResp>,
                            @JsonProperty("moodle_username") val moodleUsername: String? = null)

    data class TeachersResp(@JsonProperty("id") val id: String,
                            @JsonProperty("email") val email: String,
                            @JsonProperty("given_name") val givenName: String,
                            @JsonProperty("family_name") val familyName: String,
                            @JsonSerialize(using = DateTimeSerializer::class)
                            @JsonProperty("created_at") val createdAt: DateTime,
                            @JsonProperty("groups") val groups: List<GroupResp>)

    data class StudentPendingResp(@JsonProperty("email") val email: String,
                                  @JsonSerialize(using = DateTimeSerializer::class)
                                  @JsonProperty("valid_from") val validFrom: DateTime,
                                  @JsonProperty("groups") val groups: List<GroupResp>)

    data class StudentMoodlePendingResp(@JsonProperty("ut_username") val utUsername: String,
                                        @JsonProperty("email") val email: String,
                                        @JsonProperty("groups") val groups: List<GroupResp>)


    data class Resp(@JsonProperty("moodle_short_name")
                    @JsonInclude(Include.NON_EMPTY)
                    val moodleShortName: String?,
                    @JsonProperty("moodle_students_synced")
                    @JsonInclude(Include.NON_EMPTY)
                    val moodleStudentsSynced: Boolean?,
                    @JsonProperty("moodle_grades_synced")
                    @JsonInclude(Include.NON_EMPTY)
                    val moodleGradesSynced: Boolean?,
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
                   @RequestParam("offset", required = false) offsetStr: String?,
                   @RequestParam("limit", required = false) limitStr: String?,
                   caller: EasyUser): Resp {

        log.debug { "Getting participants on course $courseIdStr for ${caller.id} (role: $roleReq)" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        // TODO: restrict to my groups

        val moodleState = selectMoodleState(courseId)
        val shortname = moodleState?.shortname
        val syncStudents = moodleState?.syncStudents
        val syncGrades = moodleState?.syncGrades

        val offset = offsetStr?.toIntOrNull()
        val limit = limitStr?.toIntOrNull()

        when (roleReq) {
            Role.TEACHER.paramValue -> {
                val teachers = selectTeachersOnCourse(courseId, offset, limit)
                return Resp(shortname, syncStudents, syncGrades, null, teachers, null, null)
            }
            Role.STUDENT.paramValue -> {
                val students = selectStudentsOnCourse(courseId, offset, limit)
                val studentsPending = selectStudentsPendingOnCourse(courseId, offset, limit)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId, offset, limit)
                return Resp(shortname, syncStudents, syncGrades, students, null, studentsPending, studentsMoodle)
            }
            Role.ALL.paramValue, null -> {
                val students = selectStudentsOnCourse(courseId, offset, limit)
                val teachers = selectTeachersOnCourse(courseId, offset, limit)
                val studentsPending = selectStudentsPendingOnCourse(courseId, offset, limit)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId, offset, limit)
                return Resp(shortname, syncStudents, syncGrades, students, teachers, studentsPending, studentsMoodle)
            }
            else -> throw InvalidRequestException("Invalid parameter $roleReq")
        }
    }
}


private fun selectStudentsOnCourse(courseId: Long, offset: Int?, limit: Int?): List<ReadParticipantsOnCourseController.StudentsResp> {
    data class StudentOnCourse(val id: String,
                               val email: String,
                               val givenName: String,
                               val familyName: String,
                               val createdAt: DateTime,
                               val moodleUsername: String?
    )

    data class StudentGroup(val id: String, val name: String)

    return transaction {
        (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentGroupAccess leftJoin Group)
                .slice(Account.id,
                        Account.email,
                        Account.givenName,
                        Account.familyName,
                        Account.moodleUsername,
                        StudentCourseAccess.createdAt,
                        Group.id,
                        Group.name
                )
                .select { StudentCourseAccess.course eq courseId }
                .also {
                    it.limit(limit?: it.count(), offset ?: 0)
                }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            StudentOnCourse(
                                    it[Account.id].value,
                                    it[Account.email],
                                    it[Account.givenName],
                                    it[Account.familyName],
                                    it[StudentCourseAccess.createdAt],
                                    it[Account.moodleUsername]
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
                            student.createdAt,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            },
                            student.moodleUsername
                    )
                }
    }
}

private fun selectStudentsPendingOnCourse(courseId: Long, offset: Int?, limit: Int?): List<ReadParticipantsOnCourseController.StudentPendingResp> {
    data class PendingStudent(val email: String, val validFrom: DateTime)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        (StudentPendingAccess leftJoin StudentPendingGroup leftJoin Group)
                .slice(StudentPendingAccess.email,
                        StudentPendingAccess.validFrom,
                        Group.id,
                        Group.name)
                .select { StudentPendingAccess.course eq courseId }
                .also {
                    it.limit(limit?: it.count(), offset ?: 0)
                }
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

private fun selectMoodleStudentsPendingOnCourse(courseId: Long, offset: Int?, limit: Int?): List<ReadParticipantsOnCourseController.StudentMoodlePendingResp> {
    data class PendingStudent(val moodleUsername: String, val email: String)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        (StudentMoodlePendingAccess leftJoin StudentMoodlePendingGroup leftJoin Group)
                .slice(StudentMoodlePendingAccess.moodleUsername,
                        StudentMoodlePendingAccess.email,
                        Group.id,
                        Group.name)
                .select { StudentMoodlePendingAccess.course eq courseId }
                .also {
                    it.limit(limit?: it.count(), offset ?: 0)
                }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            PendingStudent(
                                    it[StudentMoodlePendingAccess.moodleUsername],
                                    it[StudentMoodlePendingAccess.email]
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
                            student.email,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            }
                    )
                }
    }
}

private fun selectTeachersOnCourse(courseId: Long, offset: Int?, limit: Int?): List<ReadParticipantsOnCourseController.TeachersResp> {
    data class TeacherOnCourse(val id: String,
                               val email: String,
                               val givenName: String,
                               val familyName: String,
                               val createdAt: DateTime
    )

    data class TeacherGroup(val id: String, val name: String)

    return transaction {
        (Account innerJoin Teacher innerJoin TeacherCourseAccess leftJoin TeacherGroupAccess leftJoin Group)
                .slice(Account.id,
                        Account.email,
                        Account.givenName,
                        Account.familyName,
                        Group.id,
                        Group.name,
                        TeacherCourseAccess.createdAt)
                .select { TeacherCourseAccess.course eq courseId }
                .also {
                    it.limit(limit?: it.count(), offset ?: 0)
                }
                .map {
                    val groupId: EntityID<Long>? = it[Group.id]
                    Pair(
                            TeacherOnCourse(
                                    it[Account.id].value,
                                    it[Account.email],
                                    it[Account.givenName],
                                    it[Account.familyName],
                                    it[TeacherCourseAccess.createdAt]
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
                            teacher.createdAt,
                            groups.filterNotNull().map {
                                ReadParticipantsOnCourseController.GroupResp(it.id, it.name)
                            }
                    )
                }
    }
}

data class CourseMoodleState(val shortname: String, val syncStudents: Boolean, val syncGrades: Boolean)

private fun selectMoodleState(courseId: Long): CourseMoodleState? {
    return transaction {
        Course.slice(Course.moodleShortName, Course.moodleSyncStudents, Course.moodleSyncGrades)
                .select { Course.id eq courseId }
                .map {
                    val shortname = it[Course.moodleShortName]
                    if (shortname != null) {
                        CourseMoodleState(
                                shortname,
                                it[Course.moodleSyncStudents],
                                it[Course.moodleSyncGrades]
                        )
                    } else null
                }
                .single()
    }
}
