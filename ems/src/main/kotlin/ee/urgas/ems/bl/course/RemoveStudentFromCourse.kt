package ee.urgas.ems.bl.course

import ee.urgas.ems.db.StudentCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class RemoveStudentFromCourseController {

    @DeleteMapping("/teacher/courses/{courseId}/students/{studentEmail}")
    fun removeStudent(@PathVariable("courseId") courseId: String,
                      @PathVariable("studentEmail") studentEmail: String) {

        log.info { "Removing student $studentEmail from course $courseId" }
        deleteStudentFromCourse(studentEmail, courseId.toLong())
    }
}


private fun deleteStudentFromCourse(email: String, courseId: Long) {
    transaction {
        StudentCourseAccess.deleteWhere {
            StudentCourseAccess.student eq email and (StudentCourseAccess.course eq courseId)
        }
    }
}
