package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Account
import core.db.Course
import core.db.Student
import core.db.StudentCourseAccess
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.NotBlank
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddStudentsToCourseController {

    data class Req(@JsonProperty("students") @field:Valid val studentIds: List<StudentIdReq>)

    data class StudentIdReq(@JsonProperty("student_id_or_email") @field:NotBlank @field:Size(max = 100) val studentIdOrEmail: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/students")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestBody @Valid body: Req, caller: EasyUser) {

        log.debug { "Adding access to course $courseIdStr to students $body" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        insertStudentCourseAccesses(courseId, body)
    }
}


private fun insertStudentCourseAccesses(courseId: Long, students: AddStudentsToCourseController.Req) {
    transaction {
        val studentIds = students.studentIds
                .map { it.studentIdOrEmail.toLowerCase() }
                .map { studentIdOrEmail ->
                    val studentWithId = Student.select { Student.id eq studentIdOrEmail }.count()

                    if (studentWithId == 0) {
                        val studentId = (Student innerJoin Account)
                                .slice(Student.id)
                                .select { Account.email.lowerCase() eq studentIdOrEmail }
                                .map { it[Student.id].value }
                                .firstOrNull()
                        studentId ?: throw InvalidRequestException("Student not found: $studentIdOrEmail",
                                ReqError.STUDENT_NOT_FOUND, "missing_student" to studentIdOrEmail)
                    } else {
                        studentIdOrEmail
                    }
                }

        val studentsWithoutAccess = studentIds.filter {
            StudentCourseAccess.select {
                StudentCourseAccess.student eq it and (StudentCourseAccess.course eq courseId)
            }.count() == 0
        }.distinct()

        log.debug { "Granting access to students (the rest already have access): $studentsWithoutAccess" }

        StudentCourseAccess.batchInsert(studentsWithoutAccess) {
            this[StudentCourseAccess.student] = EntityID(it, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }
    }
}
