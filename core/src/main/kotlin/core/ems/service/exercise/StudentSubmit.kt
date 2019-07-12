package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access.assertIsVisibleExerciseOnCourse
import core.ems.service.access.assertStudentHasAccessToCourse
import core.ems.service.autoassess.autoAssess
import core.ems.service.idToLongOrInvalidReq
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v2")
class StudentSubmitController(val autoAssessComponent: AutoAssessComponent) {

    @Value("\${easy.ems.aas.psk}")
    private lateinit var aasKey: String

    data class StudentSubmissionBody(@JsonProperty("solution", required = true) val solution: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions")
    fun createSubmission(@PathVariable("courseId") courseIdStr: String,
                         @PathVariable("courseExerciseId") courseExIdStr: String,
                         @RequestBody solutionBody: StudentSubmissionBody, caller: EasyUser) {

        log.debug { "Creating new submission by ${caller.id} on course exercise $courseExIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        assertStudentHasAccessToCourse(caller.id, courseId)
        assertIsVisibleExerciseOnCourse(courseExId, courseId)

        submitSolution(courseExId, solutionBody.solution, caller.id, aasKey)
    }

    // Must be in Spring Component to autowire autoAssessComponent
    private fun submitSolution(courseExId: Long, solution: String, studentId: String, aasKey: String) {
        val exerciseType = selectExerciseType(courseExId)

        when (exerciseType.graderType) {
            GraderType.TEACHER -> {
                log.debug { "Creating new submission to teacher-graded exercise $courseExId by $studentId" }
                insertSubmission(courseExId, solution, studentId, AutoGradeStatus.NONE)
            }
            GraderType.AUTO -> {
                log.debug { "Creating new submission to autograded exercise $courseExId by $studentId" }
                if (exerciseType.aasId == null) {
                    log.warn { "Grader type is AUTO but aas_id is null" }
                    insertSubmission(courseExId, solution, studentId, AutoGradeStatus.FAILED)
                } else {
                    val submissionId = insertSubmission(courseExId, solution, studentId, AutoGradeStatus.IN_PROGRESS)
                    autoAssessComponent.autoAssessAsync(exerciseType.aasId, solution, submissionId, aasKey)
                }
            }
        }
    }
}


data class ExerciseType(val graderType: GraderType, val aasId: String?)

private fun selectExerciseType(courseExId: Long): ExerciseType {
    return transaction {
        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(ExerciseVer.graderType, ExerciseVer.aasId)
                .select {
                    CourseExercise.id eq courseExId and ExerciseVer.validTo.isNull()
                }
                .map {
                    ExerciseType(
                            it[ExerciseVer.graderType],
                            it[ExerciseVer.aasId]
                    )
                }
                .first()
    }
}

private fun insertSubmission(courseExId: Long, solution: String, studentId: String, autoAss: AutoGradeStatus): Long {
    return transaction {
        Submission.insertAndGetId {
            it[Submission.courseExercise] = EntityID(courseExId, CourseExercise)
            it[Submission.student] = EntityID(studentId, Student)
            it[Submission.createdAt] = DateTime.now()
            it[Submission.solution] = solution
            it[Submission.autoGradeStatus] = autoAss
        }.value
    }
}

private fun insertAutoAssessment(grade: Int, feedback: String?, submissionId: Long) {
    transaction {
        AutomaticAssessment.insert {
            it[AutomaticAssessment.submission] = EntityID(submissionId, Submission)
            it[AutomaticAssessment.createdAt] = DateTime.now()
            it[AutomaticAssessment.grade] = grade
            it[AutomaticAssessment.feedback] = feedback
        }

        Submission.update({ Submission.id eq submissionId }) {
            it[Submission.autoGradeStatus] = AutoGradeStatus.COMPLETED
        }
    }
}

private fun insertAutoAssFailed(submissionId: Long) {
    transaction {
        Submission.update({ Submission.id eq submissionId }) {
            it[Submission.autoGradeStatus] = AutoGradeStatus.FAILED
        }
    }
}


@Component
class AutoAssessComponent {
    // Must be in Spring Component for Async
    @Async
    fun autoAssessAsync(aasId: String, solution: String, submissionId: Long, aasKey: String) {
        try {
            log.debug { "Starting autoassessment with aas id $aasId" }
            val autoAss = autoAssess(aasId, solution, aasKey)
            log.debug { "Finished autoassessment" }
            insertAutoAssessment(autoAss.grade, autoAss.feedback, submissionId)
        } catch (e: Exception) {
            log.error("Autoassessment failed", e)
            insertAutoAssFailed(submissionId)
        }
    }
}
