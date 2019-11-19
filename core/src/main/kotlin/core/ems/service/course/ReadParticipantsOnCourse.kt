package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.db.StudentMoodlePendingAccess.utUsername
import core.db.StudentPendingAccess.email
import core.db.StudentPendingAccess.validFrom
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class ReadParticipantsOnCourseController {

    data class StudentsOnCourseResponse(@JsonProperty("id") val id: String,
                                        @JsonProperty("email") val email: String,
                                        @JsonProperty("given_name") val givenName: String,
                                        @JsonProperty("family_name") val familyName: String)

    data class TeachersOnCourseResponse(@JsonProperty("id") val id: String,
                                        @JsonProperty("email") val email: String,
                                        @JsonProperty("given_name") val givenName: String,
                                        @JsonProperty("family_name") val familyName: String)

    data class StudentPendingAccessOnCourseResponse(@JsonProperty("email") val email: String,
                                                    @JsonSerialize(using = DateTimeSerializer::class)
                                                    @JsonProperty("valid_from") val validFrom: DateTime)

    data class StudentMoodlePendingAccessOnCourseResponse(@JsonProperty("ut_username") val utUsername: String)


    data class Resp(@JsonProperty("moodle_short_name")
                    @JsonInclude(Include.NON_EMPTY)
                    val moodleShortName: String?,
                    @JsonProperty("students")
                    @JsonInclude(Include.NON_NULL) val students: List<StudentsOnCourseResponse>?,
                    @JsonProperty("teachers")
                    @JsonInclude(Include.NON_NULL) val teachers: List<TeachersOnCourseResponse>?,
                    @JsonProperty("student_pending_access")
                    @JsonInclude(Include.NON_NULL) val studentPendingAccess: List<StudentPendingAccessOnCourseResponse>?,
                    @JsonProperty("student_moodle_pending_access")
                    @JsonInclude(Include.NON_NULL) val studentMoodlePendingAccess: List<StudentMoodlePendingAccessOnCourseResponse>?)


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

        log.debug { "Getting participants on course $courseIdStr for ${caller.id} with role $roleReq" }

        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        val shortName = selectMoodleShortName(courseId)

        when (roleReq) {
            Role.TEACHER.paramValue -> {
                val teachers = selectTeachersOnCourse(courseId)
                log.debug { "Teachers on course $courseId: $teachers" }
                return Resp(shortName, null, selectTeachersOnCourse(courseId), null, null)

            }

            Role.STUDENT.paramValue -> {
                val students = selectStudentsOnCourse(courseId)
                val studentsPending = selectStudentsPendingOnCourse(courseId)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId)
                log.debug { "Students on course $courseId: $students" }
                return Resp(shortName, selectStudentsOnCourse(courseId), null, studentsPending, studentsMoodle)
            }

            Role.ALL.paramValue, null -> {
                val students = selectStudentsOnCourse(courseId)
                val teachers = selectTeachersOnCourse(courseId)
                val studentsPending = selectStudentsPendingOnCourse(courseId)
                val studentsMoodle = selectMoodleStudentsPendingOnCourse(courseId)

                log.debug { "Students on course $courseId: $students" }
                log.debug { "Teachers on course $courseId: $teachers" }
                return Resp(shortName, selectStudentsOnCourse(courseId), selectTeachersOnCourse(courseId), studentsPending, studentsMoodle)
            }

            else -> throw InvalidRequestException("Invalid parameter $roleReq")
        }
    }
}


private fun selectStudentsOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.StudentsOnCourseResponse> {
    return transaction {
        (Account innerJoin Student innerJoin StudentCourseAccess)
                .slice(Student.id, Account.email, Account.givenName, Account.familyName)
                .select { StudentCourseAccess.course eq courseId }
                .map {
                    ReadParticipantsOnCourseController.StudentsOnCourseResponse(
                            it[Student.id].value,
                            it[Account.email],
                            it[Account.givenName],
                            it[Account.familyName]
                    )
                }
    }
}

private fun selectStudentsPendingOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.StudentPendingAccessOnCourseResponse> {
    return transaction {

        StudentPendingAccess
                .select { StudentPendingAccess.course eq courseId }
                .map {
                    ReadParticipantsOnCourseController.StudentPendingAccessOnCourseResponse(
                            it[email],
                            it[validFrom]
                    )
                }
    }
}

private fun selectMoodleStudentsPendingOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.StudentMoodlePendingAccessOnCourseResponse> {
    return transaction {

        StudentMoodlePendingAccess
                .select { StudentMoodlePendingAccess.course eq courseId }
                .map {
                    ReadParticipantsOnCourseController.StudentMoodlePendingAccessOnCourseResponse(
                            it[utUsername]
                    )
                }
    }
}

private fun selectTeachersOnCourse(courseId: Long): List<ReadParticipantsOnCourseController.TeachersOnCourseResponse> {
    return transaction {
        (Account innerJoin Teacher innerJoin TeacherCourseAccess)
                .slice(Teacher.id, Account.email, Account.givenName, Account.familyName)
                .select { TeacherCourseAccess.course eq courseId }
                .map {
                    ReadParticipantsOnCourseController.TeachersOnCourseResponse(
                            it[Teacher.id].value,
                            it[Account.email],
                            it[Account.givenName],
                            it[Account.familyName]
                    )
                }
    }
}

private fun selectMoodleShortName(courseId: Long): String? {
    return transaction {
        Course.slice(Course.moodleShortName)
                .select { Course.id eq courseId }
                .map { it[Course.moodleShortName] }
                .firstOrNull()
    }
}