package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.Teacher
import core.db.TestingDraft
import core.db.insertOrUpdate
import core.ems.service.access.assertTeacherHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid
import javax.validation.constraints.Size

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class TeacherSubmitTestingDraftController {

    data class Req(@JsonProperty("solution", required = true) @field:Size(max = 300000) val solution: String)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/teacher/courses/{courseId}/exercises/{courseExerciseId}/autoassess/draft")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExIdStr: String,
                   @Valid @RequestBody solutionBody: Req, caller: EasyUser) {

        log.debug { "Creating testing draft by ${caller.id} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertTeacherHasAccessToCourse(caller.id, courseId)

        insertOrUpdateTestingDraft(courseExId, solutionBody.solution, caller.id)
    }
}


private fun insertOrUpdateTestingDraft(courseExId: Long, submission: String, teacherId: String) {
    return transaction {
        TestingDraft.insertOrUpdate(listOf(TestingDraft.courseExercise, TestingDraft.teacher),
                listOf(TestingDraft.courseExercise, TestingDraft.teacher)) {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[teacher] = EntityID(teacherId, Teacher)
            it[createdAt] = DateTime.now()
            it[solution] = submission
        }
    }
}


