package core.ems.service.exercise.exceptions

import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.CourseExerciseExceptionGroup
import core.db.CourseExerciseExceptionStudent
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.assertCourseExerciseIsOnCourse
import core.ems.service.access_control.canStudentAccessCourse
import core.ems.service.access_control.teacherOnCourse
import core.ems.service.assertGroupExistsOnCourse
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.DateTimeDeserializer
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.validation.Valid
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.annotation.JsonDeserialize


@RestController
@RequestMapping("/v2")
class PutCourseExerciseExceptions {
    private val log = KotlinLogging.logger {}

    data class ExceptionValueReq(@param:JsonDeserialize(using = DateTimeDeserializer::class) @param:JsonProperty("value") val value: DateTime?)

    data class ReqExceptionStudent(
        @param:JsonProperty("student_id") val studentId: String,
        @param:JsonProperty("soft_deadline") val softDeadline: ExceptionValueReq?,
        @param:JsonProperty("hard_deadline") val hardDeadline: ExceptionValueReq?,
        @param:JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueReq?,
    )

    data class ReqExceptionGroup(
        @param:JsonProperty("group_id") val groupId: Long,
        @param:JsonProperty("soft_deadline") val softDeadline: ExceptionValueReq?,
        @param:JsonProperty("hard_deadline") val hardDeadline: ExceptionValueReq?,
        @param:JsonProperty("student_visible_from") val studentVisibleFrom: ExceptionValueReq?,
    )

    data class Req(
        @param:JsonProperty("exception_students") @field:Valid val exceptionStudents: List<ReqExceptionStudent>?,
        @param:JsonProperty("exception_groups") @field:Valid val exceptionGroups: List<ReqExceptionGroup>?
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @PutMapping("/courses/{courseId}/exercises/{courseExerciseId}/exception")
    fun controller(
        @PathVariable("courseId") courseIdStr: String,
        @PathVariable("courseExerciseId") courseExIdStr: String,
        @Valid @RequestBody req: Req,
        caller: EasyUser
    ) {
        log.info { "Add and/or update course exercise $courseExIdStr exceptions  on course $courseIdStr by ${caller.id}: $req" }

        val courseId = courseIdStr.idToLongOrInvalidReq()
        val courseExId = courseExIdStr.idToLongOrInvalidReq()

        caller.assertAccess { teacherOnCourse(courseId) }
        assertCourseExerciseIsOnCourse(courseExId, courseId)

        insertOrUpdateCourseExerciseExceptions(courseId, courseExId, req.exceptionStudents, req.exceptionGroups)
    }

    private fun insertOrUpdateCourseExerciseExceptions(
        courseId: Long,
        courseExId: Long,
        exceptionStudents: List<ReqExceptionStudent>?,
        exceptionGroups: List<ReqExceptionGroup>?
    ) {
        transaction {
            exceptionStudents?.forEach { ex ->

                if (!canStudentAccessCourse(ex.studentId, courseId))
                    throw InvalidRequestException(
                        "Student ${ex.studentId} not on course.",
                        ReqError.STUDENT_NOT_ON_COURSE
                    )

                CourseExerciseExceptionStudent.upsert(
                    CourseExerciseExceptionStudent.courseExercise,
                    CourseExerciseExceptionStudent.student,
                    onUpdateExclude = listOf(
                        CourseExerciseExceptionStudent.courseExercise,
                        CourseExerciseExceptionStudent.student
                    )
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
                assertGroupExistsOnCourse(ex.groupId, courseId)

                CourseExerciseExceptionGroup.upsert(
                    CourseExerciseExceptionGroup.courseExercise,
                    CourseExerciseExceptionGroup.courseGroup,
                    onUpdateExclude = listOf(
                        CourseExerciseExceptionGroup.courseExercise,
                        CourseExerciseExceptionGroup.courseGroup
                    )
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


