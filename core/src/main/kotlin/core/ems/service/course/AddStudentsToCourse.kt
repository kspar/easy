package core.ems.service.course

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.Course
import core.db.Student
import core.db.StudentCourseAccess
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddStudentsToCourseController {

    data class Req(@JsonProperty("students") @field:Valid val studentIds: List<StudentIdReq>)
    data class StudentIdReq(@JsonProperty("student_id") @field:Size(min = 1, max = 100) val studentId: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/students")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @RequestBody @Valid studentIds: Req, caller: EasyUser) {

        log.debug { "Adding access to course $courseIdStr to students $studentIds" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        insertStudentCourseAccesses(courseId, studentIds)
    }
}


private fun insertStudentCourseAccesses(courseId: Long, students: AddStudentsToCourseController.Req) {
    transaction {
        students.studentIds.forEach { student ->
            val studentExists =
                    Student.select { Student.id eq student.studentId }
                            .count() == 1
            if (!studentExists) {
                throw InvalidRequestException("Student not found: $student")
            }
        }

        val studentsWithoutAccess = students.studentIds.filter {
            StudentCourseAccess.select {
                StudentCourseAccess.student eq it.studentId and (StudentCourseAccess.course eq courseId)
            }.count() == 0
        }

        log.debug { "Granting access to students (the rest already have access): $studentsWithoutAccess" }

        StudentCourseAccess.batchInsert(studentsWithoutAccess) { student ->
            this[StudentCourseAccess.student] = EntityID(student.studentId, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }
    }
}
