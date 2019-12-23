package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddStudentsToCourseController {

    data class Req(@JsonProperty("students") @field:Valid val studentIds: List<StudentEmailsReq>)

    data class StudentEmailsReq(@JsonProperty("student_email") @field:NotBlank @field:Size(max = 100) val studentEmail: String)

    data class Resp(@JsonProperty("accesses_added") val accessesAdded: Int,
                    @JsonProperty("pending_accesses_added_updated") val pendingAccessesAddedUpdated: Int)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/students")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestBody @Valid body: Req, caller: EasyUser): Resp {

        log.debug { "Adding access to course $courseIdStr to students $body" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return insertStudentCourseAccesses(courseId, body)
    }
}


private fun insertStudentCourseAccesses(courseId: Long, students: AddStudentsToCourseController.Req): AddStudentsToCourseController.Resp {
    val time = DateTime.now()

    return transaction {
        val studentIdsWithAccount = students.studentIds
                .mapNotNull {
                    (Student innerJoin Account)
                            .slice(Student.id)
                            .select { Account.email.lowerCase() eq it.studentEmail.toLowerCase() }
                            .map { it[Student.id].value }
                            .firstOrNull()
                }

        val studentEmailsNoAccount = students.studentIds
                .mapNotNull {
                    val id = (Student innerJoin Account)
                            .slice(Student.id)
                            .select { Account.email.lowerCase() eq it.studentEmail.toLowerCase() }
                            .map { it[Student.id].value }
                            .firstOrNull()
                    if (id != null) null else it.studentEmail.toLowerCase()
                }


        val studentsAccessAddable = studentIdsWithAccount.filter {
            StudentCourseAccess.select {
                StudentCourseAccess.student eq it and (StudentCourseAccess.course eq courseId)
            }.count() == 0
        }.distinct()

        log.debug { "Granting access to students (the rest already have access): $studentsAccessAddable" }
        log.debug { "Granting pending access to students: $studentEmailsNoAccount" }

        StudentCourseAccess.batchInsert(studentsAccessAddable) {
            this[StudentCourseAccess.student] = EntityID(it, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }

        StudentPendingAccess.deleteWhere {
            StudentPendingAccess.course eq courseId and
                    (StudentPendingAccess.email inList studentEmailsNoAccount)
        }

        StudentPendingAccess.batchInsert(studentEmailsNoAccount, ignore = true) {
            this[StudentPendingAccess.course] = EntityID(courseId, Course)
            this[StudentPendingAccess.email] = it
            this[StudentPendingAccess.validFrom] = time
        }

        AddStudentsToCourseController.Resp(studentsAccessAddable.size, studentEmailsNoAccount.size)
    }
}
