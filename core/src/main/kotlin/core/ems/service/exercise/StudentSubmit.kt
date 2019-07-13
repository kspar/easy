package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.aas.service.autoAssess
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertIsVisibleExerciseOnCourse
import core.ems.service.access.assertStudentHasAccessToCourse
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.scheduling.annotation.Async
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentSubmitCont(val autoAssessComponent: AutoAssessComponent) {

    data class Req(
            @JsonProperty("solution", required = true) val solution: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions")
    fun controller(@PathVariable("courseId") courseIdStr: String,
                   @PathVariable("courseExerciseId") courseExIdStr: String,
                   @RequestBody solutionBody: Req, caller: EasyUser) {

        log.debug { "Creating new submission by ${caller.id} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertIsVisibleExerciseOnCourse(courseExId, courseId)

        submitSolution(courseExId, solutionBody.solution, caller.id)
    }

    // Must be in Spring Component to autowire autoAssessComponent
    private fun submitSolution(courseExId: Long, solution: String, studentId: String) {
        when (selectGraderType(courseExId)) {
            GraderType.TEACHER -> {
                log.debug { "Creating new submission to teacher-graded exercise $courseExId by $studentId" }
                insertSubmission(courseExId, solution, studentId, AutoGradeStatus.NONE)
            }
            GraderType.AUTO -> {
                log.debug { "Creating new submission to autograded exercise $courseExId by $studentId" }
                val submissionId = insertSubmission(courseExId, solution, studentId, AutoGradeStatus.IN_PROGRESS)
                autoAssessComponent.autoAssessAsync(courseExId, solution, submissionId)
            }
        }
    }
}


private fun selectGraderType(courseExId: Long): GraderType {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.graderType)
                .select { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
                .map { it[ExerciseVer.graderType] }
                .single()
    }
}

private fun selectAutoExId(courseExId: Long): EntityID<Long>? {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.autoExerciseId)
                .select { CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull() }
                .map { it[ExerciseVer.autoExerciseId] }
                .single()
    }
}

private fun insertSubmission(courseExId: Long, submission: String, studentId: String, autoAss: AutoGradeStatus): Long {
    return transaction {
        Submission.insertAndGetId {
            it[courseExercise] = EntityID(courseExId, CourseExercise)
            it[student] = EntityID(studentId, Student)
            it[createdAt] = DateTime.now()
            it[solution] = submission
            it[autoGradeStatus] = autoAss
        }.value
    }
}

private fun insertAutoAssessment(newGrade: Int, newFeedback: String?, submissionId: Long) {
    transaction {
        AutomaticAssessment.insert {
            it[submission] = EntityID(submissionId, Submission)
            it[createdAt] = DateTime.now()
            it[grade] = newGrade
            it[feedback] = newFeedback
        }

        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.COMPLETED
        }
    }
}

private fun insertAutoAssFailed(submissionId: Long) {
    transaction {
        Submission.update({ Submission.id eq submissionId }) {
            it[autoGradeStatus] = AutoGradeStatus.FAILED
        }
    }
}


@Component
class AutoAssessComponent {
    // TODO: move to controller
    // Must be in Spring Component for Async
    @Async
    fun autoAssessAsync(courseExId: Long, solution: String, submissionId: Long) {
        try {
            val autoExerciseId = selectAutoExId(courseExId)
            if (autoExerciseId == null) {
                insertAutoAssFailed(submissionId)
                throw IllegalStateException("Exercise grader type is AUTO but auto exercise id is null")
            }

            log.debug { "Starting autoassessment with auto exercise id $autoExerciseId" }
            val autoAss = autoAssess(autoExerciseId, solution)
            log.debug { "Finished autoassessment" }
            insertAutoAssessment(autoAss.grade, autoAss.feedback, submissionId)
        } catch (e: Exception) {
            log.error("Autoassessment failed", e)
            insertAutoAssFailed(submissionId)
        }
    }
}
