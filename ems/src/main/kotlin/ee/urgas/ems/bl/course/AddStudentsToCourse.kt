package ee.urgas.ems.bl.course

import ee.urgas.ems.db.Course
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.StudentCourseAccess
import ee.urgas.ems.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class AddStudentsToCourseController {

    @PostMapping("/teacher/courses/{courseId}/students")
    fun addStudentsToCourse(@PathVariable("courseId") courseId: String,
                            @RequestBody studentEmails: List<String>) {

        // TODO: access control
        log.debug { "Adding access to course $courseId to students $studentEmails" }
        try {
            insertStudentCourseAccesses(courseId.toLong(), studentEmails)
        } catch (e: StudentNotFoundException) {
            log.info { "Adding failed, student not found: ${e.message}" }
            throw InvalidRequestException(e.message)
        }
    }
}

class StudentNotFoundException(override val message: String) : RuntimeException(message)

private fun insertStudentCourseAccesses(courseId: Long, emails: List<String>) {
    transaction {
        emails.forEach { email ->
            val studentExists =
                    Student.select { Student.id eq email }
                            .count() == 1
            if (!studentExists) {
                throw StudentNotFoundException(email)
            }
        }

        val studentsWithoutAccess = emails.filter {
            StudentCourseAccess.select {
                StudentCourseAccess.student eq it and (StudentCourseAccess.course eq courseId)
            }.count() == 0
        }

        log.debug { "Granting access to students (the rest already have access): $studentsWithoutAccess" }

        StudentCourseAccess.batchInsert(studentsWithoutAccess) { email ->
            this[StudentCourseAccess.student] = EntityID(email, Student)
            this[StudentCourseAccess.course] = EntityID(courseId, Course)
        }
    }
}
