package core.ems.service.course

import core.conf.security.EasyUser
import core.db.Course
import core.db.Student
import core.db.StudentCourseAccess
import core.ems.service.access.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class AddStudentsToCourseController {

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/students")
    fun addStudentsToCourse(@PathVariable("courseId") courseIdStr: String,
                            @RequestBody studentIds: List<String>,
                            caller: EasyUser) {

        log.debug { "Adding access to course $courseIdStr to students $studentIds" }
        val courseId = courseIdStr.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        insertStudentCourseAccesses(courseId, studentIds)
    }
}

// TODO: use InvalidRequestException with custom code
class StudentNotFoundException(override val message: String) : RuntimeException(message)

private fun insertStudentCourseAccesses(courseId: Long, studentIds: List<String>) {
    transaction {
        studentIds.forEach { studentId ->
            val studentExists =
                    Student.select { Student.id eq studentId }
                            .count() == 1
            if (!studentExists) {
                throw StudentNotFoundException(studentId)
            }
        }

        val studentsWithoutAccess = studentIds.filter {
            StudentCourseAccess.select {
                StudentCourseAccess.student eq it and (StudentCourseAccess.course eq courseId)
            }.count() == 0
        }

        log.debug { "Granting access to students (the rest already have access): $studentsWithoutAccess" }

        StudentCourseAccess.batchInsert(studentsWithoutAccess) { id ->
            this[StudentCourseAccess.student] = EntityID(id, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }
    }
}
