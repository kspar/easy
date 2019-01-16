package ee.urgas.ems.bl.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import ee.urgas.ems.bl.access.canStudentAccessCourse
import ee.urgas.ems.bl.access.isVisibleExerciseOnCourse
import ee.urgas.ems.bl.autoassess.autoAssess
import ee.urgas.ems.conf.security.EasyUser
import ee.urgas.ems.db.AutoGradeStatus
import ee.urgas.ems.db.AutomaticAssessment
import ee.urgas.ems.db.CourseExercise
import ee.urgas.ems.db.Exercise
import ee.urgas.ems.db.ExerciseVer
import ee.urgas.ems.db.GraderType
import ee.urgas.ems.db.Student
import ee.urgas.ems.db.Submission
import ee.urgas.ems.exception.ForbiddenException
import ee.urgas.ems.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.joda.time.DateTime
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.security.access.annotation.Secured
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/v1")
class StudentSubmitController(val autoAssessComponent: AutoAssessComponent) {

    @Value("\${easy.ems.aas.psk}")
    private lateinit var aasKey: String

    data class StudentSubmissionBody(@JsonProperty("solution", required = true) val solution: String)

    @Secured("ROLE_STUDENT")
    @PostMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions")
    fun createSubmission(@PathVariable("courseId") courseIdStr: String,
                         @PathVariable("courseExerciseId") courseExIdStr: String,
                         @RequestBody solutionBody: StudentSubmissionBody, caller: EasyUser) {

        val callerEmail = caller.email
        val courseId = courseIdStr.toLong()
        val courseExId = courseExIdStr.toLong()

        if (!canStudentAccessCourse(callerEmail, courseId)) {
            throw ForbiddenException("Student $callerEmail does not have access to course $courseId")
        }

        if (!isVisibleExerciseOnCourse(courseExId, courseId)) {
            throw InvalidRequestException("Exercise $courseExId not found on course $courseId or it is hidden")
        }

        submitSolution(courseExId, solutionBody.solution, callerEmail, aasKey)
    }

    // Must be in Spring Component to autowire autoAssessComponent
    private fun submitSolution(courseExId: Long, solution: String, studentId: String, aasKey: String) {
        val exerciseType = selectExerciseType(courseExId)

        when (exerciseType.graderType) {
            GraderType.TEACHER -> {
                log.debug { "Creating new submission to teacher-graded exercise $courseExId by $studentId: $solution" }
                insertSubmission(courseExId, solution, studentId, AutoGradeStatus.NONE)
            }
            GraderType.AUTO -> {
                log.debug { "Creating new submission to autograded exercise $courseExId by $studentId: $solution" }
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
            log.debug { "Finished autoassessment: $autoAss" }
            insertAutoAssessment(autoAss.grade, autoAss.feedback, submissionId)
        } catch (e: Exception) {
            log.error("Autoassessment failed", e)
            insertAutoAssFailed(submissionId)
        }
    }
}
