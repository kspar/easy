package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.StudentCourseAccess
import core.db.TeacherCourseAccess
import core.ems.service.assertCourseExists
import core.ems.service.determineCourseExerciseVisibleFrom
import core.ems.service.selectCourseExerciseExceptions
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


/**
 *  Has teacher-access to this course.
 */
fun AccessChecksBuilder.teacherOnCourse(courseId: Long) = add { caller: EasyUser ->
    when {
        caller.isAdmin() -> assertCourseExists(courseId)
        caller.isTeacher() -> assertTeacherCanAccessCourse(caller.id, courseId)
        else -> throw ForbiddenException("Role not allowed", ReqError.ROLE_NOT_ALLOWED)
    }
}


fun AccessChecksBuilder.userOnCourse(courseId: Long) = add { caller: EasyUser ->
    when {
        caller.isAdmin() -> assertCourseExists(courseId)
        caller.isTeacher() && canTeacherAccessCourse(caller.id, courseId) -> {}
        caller.isStudent() && canStudentAccessCourse(caller.id, courseId) -> {}
        else -> throw ForbiddenException(
            "User ${caller.id} does not have access to course $courseId", ReqError.NO_COURSE_ACCESS
        )
    }
}

/**
 *  Assert that student has access to course.
 */
fun AccessChecksBuilder.studentOnCourse(courseId: Long) = add { caller: EasyUser ->
    assertStudentHasAccessToCourse(caller.id, courseId)
}

/**
 * Can access this course and this exercise is on that course (can be hidden).
 */
fun AccessChecksBuilder.exerciseViaCourse(exerciseId: Long, courseId: Long) = add { caller: EasyUser ->
    when {
        caller.isAdmin() -> {}

        caller.isTeacher() -> {
            assertTeacherCanAccessCourse(caller.id, courseId)
            assertExerciseIsOnCourse(exerciseId, courseId)
        }

        else -> throw ForbiddenException("Role not allowed", ReqError.ROLE_NOT_ALLOWED)
    }
}

fun assertCourseExerciseIsOnCourse(
    courseExId: Long,
    courseId: Long,
    requireStudentVisible: RequireStudentVisible? = null
) {
    if (!isCourseExerciseOnCourse(courseExId, courseId, requireStudentVisible)) {
        throw InvalidRequestException(
            "Course exercise $courseExId not found on course $courseId " + if (requireStudentVisible != null) "or it is hidden" else "",
            ReqError.ENTITY_WITH_ID_NOT_FOUND,
            notify = false
        )
    }
}

fun isExerciseOnCourse(exerciseId: Long, courseId: Long): Boolean = transaction {
    CourseExercise.selectAll()
        .where { CourseExercise.course eq courseId and (CourseExercise.exercise eq exerciseId) }
        .count() > 0
}

data class RequireStudentVisible(val studentId: String)

private fun isCourseExerciseOnCourse(
    courseExId: Long,
    courseId: Long,
    requireStudentVisible: RequireStudentVisible?
): Boolean =
    transaction {
        val query =
            CourseExercise.select(CourseExercise.id, CourseExercise.studentVisibleFrom)
                .where { CourseExercise.course eq courseId and (CourseExercise.id eq courseExId) }

        val courseExerciseOnCourse = query.count() > 0

        if (requireStudentVisible == null) {
            courseExerciseOnCourse
        } else {
            val defaultVisible = query.map { it[CourseExercise.studentVisibleFrom] }.singleOrNull()

            val exceptions = selectCourseExerciseExceptions(courseExId, requireStudentVisible.studentId)
            val visibleFrom = determineCourseExerciseVisibleFrom(
                exceptions,
                courseExId,
                requireStudentVisible.studentId,
                defaultVisible
            )

            val isHidden = visibleFrom == null || visibleFrom.isAfterNow
            !isHidden && courseExerciseOnCourse
        }
    }

fun assertExerciseIsNotOnAnyCourse(exerciseId: Long) = transaction {
    val coursesCount = CourseExercise.selectAll().where { CourseExercise.exercise eq exerciseId }.count()
    if (coursesCount > 0)
        throw InvalidRequestException(
            "Exercise $exerciseId is used on $coursesCount courses",
            ReqError.EXERCISE_USED_ON_COURSE, "id" to exerciseId.toString()
        )
}

fun canTeacherAccessCourse(teacherId: String, courseId: Long): Boolean = transaction {
    TeacherCourseAccess.selectAll()
        .where { TeacherCourseAccess.teacher eq teacherId and (TeacherCourseAccess.course eq courseId) }.count() > 0
}

fun canStudentAccessCourse(studentId: String, courseId: Long): Boolean = transaction {
    StudentCourseAccess.selectAll()
        .where { StudentCourseAccess.student eq studentId and (StudentCourseAccess.course eq courseId) }.count() > 0
}


private fun assertTeacherCanAccessCourse(teacherId: String, courseId: Long) {
    if (!canTeacherAccessCourse(teacherId, courseId)) {
        throw ForbiddenException(
            "Teacher $teacherId does not have access to course $courseId", ReqError.NO_COURSE_ACCESS
        )
    }
}


private fun assertExerciseIsOnCourse(exerciseId: Long, courseId: Long) {
    if (!isExerciseOnCourse(exerciseId, courseId)) {
        throw ForbiddenException(
            "Exercise $exerciseId not found on course $courseId",
            ReqError.ENTITY_WITH_ID_NOT_FOUND
        )
    }
}

private fun assertStudentHasAccessToCourse(studentId: String, courseId: Long) {
    if (!canStudentAccessCourse(studentId, courseId)) {
        throw ForbiddenException(
            "Student $studentId does not have access to course $courseId", ReqError.NO_COURSE_ACCESS
        )
    }
}