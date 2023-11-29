package core.ems.service.exercise

import core.aas.AutoAssessStatusObserver
import core.aas.ObserverCallerType
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.studentOnCourse
import core.ems.service.idToLongOrInvalidReq
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class StudentAwaitLatestSubmissionController(private val autoAssessStatusObserver: AutoAssessStatusObserver) {
    private val log = KotlinLogging.logger {}

    @Secured("ROLE_STUDENT")
    @GetMapping("/student/courses/{courseId}/exercises/{courseExerciseId}/submissions/latest/await")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExerciseIdStr: String,
        caller: EasyUser
    ) {

        log.info { "Getting latest submission for student ${caller.id} on course exercise $courseExerciseIdStr on course $courseIdStr" }
        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExerciseIdStr.idToLongOrInvalidReq()

        caller.assertAccess { studentOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        runBlocking {
            waitLatestStudentSubmission(
                courseId,
                courseExId,
                caller.id,
            )
        }
    }

    private suspend fun waitLatestStudentSubmission(courseId: Long, courseExId: Long, studentId: String) {
        val latestSubmissionId = lastSubmissionId(courseId, courseExId, studentId) ?: return
        // Wait while automatic grading in progress
        autoAssessStatusObserver.get(latestSubmissionId, ObserverCallerType.STUDENT)?.join()
    }

    private fun lastSubmissionId(courseId: Long, courseExId: Long, studentId: String): Long? = transaction {
        (CourseExercise innerJoin Submission)
            .slice(Submission.id)
            .select {
                CourseExercise.course eq courseId and
                        (CourseExercise.id eq courseExId) and
                        (Submission.student eq studentId)
            }
            .orderBy(Submission.createdAt to SortOrder.DESC)
            .limit(1)
            .map { it[Submission.id].value }
            .firstOrNull()
    }
}
