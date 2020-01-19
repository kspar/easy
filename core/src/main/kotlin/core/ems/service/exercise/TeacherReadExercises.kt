package core.ems.service.exercise

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.assertTeacherOrAdminHasAccessToCourse
import core.ems.service.getTeacherRestrictedGroups
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


private val log = KotlinLogging.logger {}


@RestController
@RequestMapping("/v2")
class TeacherReadCourseExercisesController {

    data class CourseExerciseResp(@JsonProperty("id") val id: String,
                                  @JsonProperty("effective_title") val title: String,
                                  @JsonSerialize(using = DateTimeSerializer::class)
                                  @JsonProperty("soft_deadline") val softDeadline: DateTime?,
                                  @JsonProperty("grader_type") val graderType: GraderType,
                                  @JsonProperty("ordering_idx") val orderingIndex: Int,
                                  @JsonProperty("unstarted_count") val unstartedCount: Int,
                                  @JsonProperty("ungraded_count") val ungradedCount: Int,
                                  @JsonProperty("started_count") val startedCount: Int,
                                  @JsonProperty("completed_count") val completedCount: Int)

    data class Resp(@JsonProperty("exercises") val courseExercises: List<CourseExerciseResp>)

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/teacher/courses/{courseId}/exercises")
    fun controller(@PathVariable("courseId") courseIdString: String,
                   caller: EasyUser): Resp {

        log.debug { "Getting exercises on course $courseIdString for teacher/admin ${caller.id}" }
        val courseId = courseIdString.idToLongOrInvalidReq()

        assertTeacherOrAdminHasAccessToCourse(caller, courseId)

        return Resp(selectTeacherExercisesOnCourse(courseId, caller.id))
    }
}


private fun selectTeacherExercisesOnCourse(courseId: Long, callerId: String):
        List<TeacherReadCourseExercisesController.CourseExerciseResp> {

    return transaction {

        val restrictedGroups = getTeacherRestrictedGroups(courseId, callerId)

        val studentQuery = (StudentCourseAccess leftJoin StudentGroupAccess)
                .slice(StudentCourseAccess.student)
                .select { StudentCourseAccess.course eq courseId }
                .withDistinct()

        if (restrictedGroups.isNotEmpty()) {
            studentQuery.andWhere {
                StudentGroupAccess.group inList restrictedGroups or
                        (StudentGroupAccess.group.isNull())
            }
        }

        val studentCount = studentQuery.count()

        (CourseExercise innerJoin Exercise innerJoin ExerciseVer)
                .slice(CourseExercise.id, CourseExercise.gradeThreshold, CourseExercise.softDeadline,
                        ExerciseVer.graderType, ExerciseVer.title, CourseExercise.titleAlias)
                .select {
                    CourseExercise.course eq courseId and
                            ExerciseVer.validTo.isNull()
                }
                .orderBy(CourseExercise.orderIdx, SortOrder.ASC)
                .mapIndexed { i, ex ->

                    val ceId = ex[CourseExercise.id]

                    val distinctStudentId = StudentCourseAccess.student.distinctOn().alias("studentId")
                    val validGrade = Coalesce(TeacherAssessment.grade, AutomaticAssessment.grade).alias("validGrade")

                    val query = (Join(Submission, StudentCourseAccess leftJoin StudentGroupAccess,
                            onColumn = Submission.student, otherColumn = StudentCourseAccess.student)
                            leftJoin AutomaticAssessment leftJoin TeacherAssessment)
                            .slice(distinctStudentId, validGrade)
                            .select { Submission.courseExercise eq ceId and
                                    (StudentCourseAccess.course eq courseId) }
                            .orderBy(distinctStudentId to SortOrder.ASC,
                                    Submission.createdAt to SortOrder.DESC,
                                    AutomaticAssessment.createdAt to SortOrder.DESC,
                                    TeacherAssessment.createdAt to SortOrder.DESC)

                    if (restrictedGroups.isNotEmpty()) {
                        query.andWhere {
                            StudentGroupAccess.group inList restrictedGroups or
                                    (StudentGroupAccess.group.isNull())
                        }
                    }

                    val latestSubmissionGrades = query.map {
                        val validGrade: Int? = it[validGrade]
                        validGrade
                    }

                    val gradeThreshold = ex[CourseExercise.gradeThreshold]

                    val unstartedCount = studentCount - latestSubmissionGrades.size
                    val ungradedCount = latestSubmissionGrades.count { it == null }
                    val startedCount = latestSubmissionGrades.count { it != null && it < gradeThreshold }
                    val completedCount = latestSubmissionGrades.count { it != null && it >= gradeThreshold }

                    // Sanity check
                    if (unstartedCount + ungradedCount + startedCount + completedCount != studentCount)
                        log.warn {
                            "Student grade sanity check failed. unstarted: $unstartedCount, ungraded: $ungradedCount, " +
                                    "started: $startedCount, completed: $completedCount, students in course: $studentCount"
                        }

                    TeacherReadCourseExercisesController.CourseExerciseResp(
                            ex[CourseExercise.id].value.toString(),
                            ex[CourseExercise.titleAlias] ?: ex[ExerciseVer.title],
                            ex[CourseExercise.softDeadline],
                            ex[ExerciseVer.graderType],
                            i,
                            unstartedCount,
                            ungradedCount,
                            startedCount,
                            completedCount
                    )
                }
    }
}
