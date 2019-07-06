package ee.urgas.ems.bl.course

import ee.urgas.ems.bl.idToLongOrInvalidReq
import ee.urgas.ems.db.StudentCourseAccess
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class RemoveStudentFromCourseController {

    @Secured("ROLE_TEACHER")
    @DeleteMapping("/teacher/courses/{courseId}/students/{studentId}")
    fun removeStudent(@PathVariable("courseId") courseId: String,
                      @PathVariable("studentId") studentId: String) {

        // TODO: access control
        log.info { "Removing student $studentId from course $courseId" }
        deleteStudentFromCourse(studentId, courseId.idToLongOrInvalidReq())
    }
}


private fun deleteStudentFromCourse(id: String, courseId: Long) {
    transaction {
        StudentCourseAccess.deleteWhere {
            StudentCourseAccess.student eq id and (StudentCourseAccess.course eq courseId)
        }
    }
}
