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
                            @JsonProperty("created_at") val createdAt: DateTime?,
                            @JsonProperty("groups") val groups: List<GroupResp>,
                            @JsonProperty("moodle_username") val moodleUsername: String? = null)

    data class TeachersResp(@JsonProperty("id") val id: String,
                            @JsonProperty("email") val email: String,
                            @JsonProperty("given_name") val givenName: String,
                            @JsonProperty("family_name") val familyName: String,
                            @JsonSerialize(using = DateTimeSerializer::class)
                            @JsonProperty("created_at") val createdAt: DateTime?,
                            @JsonProperty("groups") val groups: List<GroupResp>)

    data class StudentPendingResp(@JsonProperty("email") val email: String,
                                  @JsonSerialize(using = DateTimeSerializer::class)
                                  @JsonProperty("valid_from") val validFrom: DateTime?,
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
                    @JsonProperty("student_count")
                    @JsonInclude(Include.NON_EMPTY)
                    val studentCount: Long?,
                    @JsonProperty("teacher_count")
                    @JsonInclude(Include.NON_EMPTY)
                    val teacherCount: Long?,
                    @JsonProperty("students_pending_count")
                    @JsonInclude(Include.NON_EMPTY)
                    val studentsPendingCount: Long?,
                    @JsonProperty("students_moodle_pending_count")
                    @JsonInclude(Include.NON_EMPTY)
                    val studentsMoodlePendingCount: Long?,
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

        val offset = offsetStr?.toLongOrNull()
        val limit = limitStr?.toIntOrNull()

        when (roleReq) {
            Role.TEACHER.paramValue -> {
                val teachersPair = selectTeachersOnCourse(courseId, offset, limit)
                return Resp(
                        shortname,
                        syncStudents,
                        syncGrades,
                        null,
                        teachersPair.second,
                        null,
                        null,
                        null,
                        teachersPair.first,
                        null,
                        null
                )
            }
            Role.STUDENT.paramValue -> {
                val studentsPair = selectStudentsOnCourse(courseId, offset, limit)
                val studentsPendingPair = selectStudentsPendingOnCourse(courseId, offset, limit)
                val studentsMoodlePair = selectMoodleStudentsPendingOnCourse(courseId, offset, limit)
                return Resp(
                        shortname,
                        syncStudents,
                        syncGrades,
                        studentsPair.second,
                        null,
                        studentsPendingPair.second,
                        studentsMoodlePair.second,
                        studentsPair.first,
                        null,
                        studentsPendingPair.first,
                        studentsMoodlePair.first
                )
            }
            Role.ALL.paramValue, null -> {
                val studentsPair = selectStudentsOnCourse(courseId, offset, limit)
                val teachersPair = selectTeachersOnCourse(courseId, offset, limit)
                val studentsPendingPair = selectStudentsPendingOnCourse(courseId, offset, limit)
                val studentsMoodlePair = selectMoodleStudentsPendingOnCourse(courseId, offset, limit)
                return Resp(
                        shortname,
                        syncStudents,
                        syncGrades,
                        studentsPair.second,
                        teachersPair.second,
                        studentsPendingPair.second,
                        studentsMoodlePair.second,
                        studentsPair.first,
                        teachersPair.first,
                        studentsPendingPair.first,
                        studentsMoodlePair.first
                )
            }
            else -> throw InvalidRequestException("Invalid parameter $roleReq")
        }
    }
}


private fun selectStudentsOnCourse(courseId: Long, offset: Long?, limit: Int?): Pair<List<ReadParticipantsOnCourseController.StudentsResp>, Long> {
    data class StudentOnCourse(val id: String,
                               val email: String,
                               val givenName: String,
                               val familyName: String,
                               val createdAt: DateTime?,
                               val moodleUsername: String?
    )

    data class StudentGroup(val id: String, val name: String)

    return transaction {
        val selectQuery = (Account innerJoin Student innerJoin StudentCourseAccess leftJoin StudentCourseGroup leftJoin CourseGroup)
                .slice(Account.id,
                        Account.email,
                        Account.givenName,
                        Account.familyName,
                        Account.moodleUsername,
                        StudentCourseAccess.createdAt,
                        CourseGroup.id,
                        CourseGroup.name
                )
                .select { StudentCourseAccess.course eq courseId }

        val count = selectQuery.count()

        Pair(
                selectQuery.limit(limit ?: count.toInt(), offset ?: 0)
                        .map {
                            val groupId: EntityID<Long>? = it[CourseGroup.id]
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
                                                it[CourseGroup.name]
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
                        },
                count)
    }
}

private fun selectStudentsPendingOnCourse(courseId: Long, offset: Long?, limit: Int?): Pair<List<ReadParticipantsOnCourseController.StudentPendingResp>, Long> {
    data class PendingStudent(val email: String, val validFrom: DateTime)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        val selectQuery = (StudentPendingAccess leftJoin StudentPendingCourseGroup leftJoin CourseGroup)
                .slice(StudentPendingAccess.email,
                        StudentPendingAccess.validFrom,
                        CourseGroup.id,
                        CourseGroup.name)
                .select { StudentPendingAccess.course eq courseId }

        val count = selectQuery.count()

        Pair(
                selectQuery.limit(limit ?: count.toInt(), offset ?: 0)
                        .map {
                            val groupId: EntityID<Long>? = it[CourseGroup.id]
                            Pair(
                                    PendingStudent(
                                            it[StudentPendingAccess.email],
                                            it[StudentPendingAccess.validFrom]
                                    ),
                                    if (groupId == null) null else
                                        StudentGroup(
                                                groupId.value.toString(),
                                                it[CourseGroup.name]
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
                        },
                count)
    }
}

private fun selectMoodleStudentsPendingOnCourse(courseId: Long, offset: Long?, limit: Int?): Pair<List<ReadParticipantsOnCourseController.StudentMoodlePendingResp>, Long> {
    data class PendingStudent(val moodleUsername: String, val email: String)
    data class StudentGroup(val id: String, val name: String)

    return transaction {
        val selectQuery = (StudentMoodlePendingAccess leftJoin StudentMoodlePendingCourseGroup leftJoin CourseGroup)
                .slice(StudentMoodlePendingAccess.moodleUsername,
                        StudentMoodlePendingAccess.email,
                        CourseGroup.id,
                        CourseGroup.name)
                .select { StudentMoodlePendingAccess.course eq courseId }

        val count = selectQuery.count()

        Pair(
                selectQuery.limit(limit ?: count.toInt(), offset ?: 0)
                        .map {
                            val groupId: EntityID<Long>? = it[CourseGroup.id]
                            Pair(
                                    PendingStudent(
                                            it[StudentMoodlePendingAccess.moodleUsername],
                                            it[StudentMoodlePendingAccess.email]
                                    ),
                                    if (groupId == null) null else
                                        StudentGroup(
                                                groupId.value.toString(),
                                                it[CourseGroup.name]
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
                        },
                count)
    }
}

private fun selectTeachersOnCourse(courseId: Long, offset: Long?, limit: Int?): Pair<List<ReadParticipantsOnCourseController.TeachersResp>, Long> {
    data class TeacherOnCourse(val id: String,
                               val email: String,
                               val givenName: String,
                               val familyName: String,
                               val createdAt: DateTime?
    )

    data class TeacherGroup(val id: String, val name: String)

    return transaction {
        val selectQuery = (Account innerJoin Teacher innerJoin TeacherCourseAccess leftJoin TeacherCourseGroup leftJoin CourseGroup)
                .slice(Account.id,
                        Account.email,
                        Account.givenName,
                        Account.familyName,
                        CourseGroup.id,
                        CourseGroup.name,
                        TeacherCourseAccess.createdAt)
                .select { TeacherCourseAccess.course eq courseId }

        val count = selectQuery.count()

        Pair(
                selectQuery.limit(limit ?: count.toInt(), offset ?: 0)
                        .map {
                            val groupId: EntityID<Long>? = it[CourseGroup.id]
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
                                                it[CourseGroup.name]
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
                        },
                count)
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
