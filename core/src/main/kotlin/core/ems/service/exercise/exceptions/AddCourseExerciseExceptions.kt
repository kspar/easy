package core.ems.service.exercise.exceptions

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import core.conf.security.EasyUser
import core.db.CourseExerciseExceptionGroup
import core.db.CourseExerciseExceptionStudent
import core.db.insertOrUpdate
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeDeserializer
import mu.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import javax.validation.Valid


@RestController
@RequestMapping("/v2")
class AddCourseExerciseExceptions {
    private val log = KotlinLogging.logger {}

    data class ExceptionValueReq(@JsonDeserialize(using = DateTimeDeserializer::class) @JsonProperty("value") val value: DateTime?)

    data class ReqExceptionStudent(
        @JsonProperty("student_id") val studentId: String,
        @JsonProperty("soft_deadline") val softDeadline: ExceptionValueReq?,
        @JsonProperty("hard_deadline") val hardDeadline: ExceptionValueReq?,
        @JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueReq?,
    )

    data class ReqExceptionGroup(
        @JsonProperty("group_id") val groupId: Long,
        @JsonProperty("soft_deadline") val softDeadline: ExceptionValueReq?,
        @JsonProperty("hard_deadline") val hardDeadline: ExceptionValueReq?,
        @JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueReq?,
    )

    data class Req(
        @JsonProperty("exception_students") @field:Valid val exceptionStudents: List<ReqExceptionStudent>?,
        @JsonProperty("exception_groups") @field:Valid val exceptionGroups: List<ReqExceptionGroup>?
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PostMapping("/courses/{courseId}/exercises/{courseExerciseId}/exception")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Update course exercise $courseExIdStr on course $courseIdStr by ${caller.id}: $req" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        insertOrUpdateCourseExerciseExceptions(courseExId, req.exceptionStudents, req.exceptionGroups)
    }

    private fun insertOrUpdateCourseExerciseExceptions(
        courseExId: Long,
        exceptionStudents: List<ReqExceptionStudent>?,
        exceptionGroups: List<ReqExceptionGroup>?
    ) {
        transaction {
            exceptionStudents?.forEach { ex ->
                CourseExerciseExceptionStudent.insertOrUpdate(
                    listOf(CourseExerciseExceptionStudent.courseExercise, CourseExerciseExceptionStudent.student),
                    listOf(CourseExerciseExceptionStudent.courseExercise, CourseExerciseExceptionStudent.student),
                ) {
                    it[CourseExerciseExceptionStudent.courseExercise] = courseExId
                    it[CourseExerciseExceptionStudent.student] = ex.studentId
                    it[CourseExerciseExceptionStudent.isExceptionSoftDeadline] = ex.softDeadline != null
                    it[CourseExerciseExceptionStudent.softDeadline] = ex.softDeadline?.value
                    it[CourseExerciseExceptionStudent.isExceptionHardDeadline] = ex.hardDeadline != null
                    it[CourseExerciseExceptionStudent.hardDeadline] = ex.hardDeadline?.value
                    it[CourseExerciseExceptionStudent.isExceptionStudentVisibleFrom] = ex.studentVisibleFrom != null
                    it[CourseExerciseExceptionStudent.studentVisibleFrom] = ex.studentVisibleFrom?.value
                }
            }

            exceptionGroups?.forEach { ex ->
                CourseExerciseExceptionGroup.insertOrUpdate(
                    listOf(CourseExerciseExceptionGroup.courseExercise, CourseExerciseExceptionGroup.courseGroup),
                    listOf(CourseExerciseExceptionGroup.courseExercise, CourseExerciseExceptionGroup.courseGroup),
                ) {
                    it[CourseExerciseExceptionGroup.courseExercise] = courseExId
                    it[CourseExerciseExceptionGroup.courseGroup] = ex.groupId
                    it[CourseExerciseExceptionGroup.isExceptionSoftDeadline] = ex.softDeadline != null
                    it[CourseExerciseExceptionGroup.softDeadline] = ex.softDeadline?.value
                    it[CourseExerciseExceptionGroup.isExceptionHardDeadline] = ex.hardDeadline != null
                    it[CourseExerciseExceptionGroup.hardDeadline] = ex.hardDeadline?.value
                    it[CourseExerciseExceptionGroup.isExceptionStudentVisibleFrom] = ex.studentVisibleFrom != null
                    it[CourseExerciseExceptionGroup.studentVisibleFrom] = ex.studentVisibleFrom?.value
                }
            }
        }
    }
}


