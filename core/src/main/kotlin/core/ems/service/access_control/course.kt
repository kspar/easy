package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.StudentCourseAccess
import core.db.TeacherCourseAccess
import core.db.TeacherCourseGroup
import core.ems.service.assertCourseExists
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

private val log = KotlinLogging.logger {}

/**
 *  Has teacher-access to this course.
 */
fun AccessChecksBuilder.teacherOnCourse(courseId: Long, allowRestrictedGroups: Boolean) = add { caller: EasyUser ->
    when {
        caller.isAdmin() -> {}

        caller.isTeacher() -> {
            assertTeacherCanAccessCourse(caller.id, courseId)
            if (!allowRestrictedGroups) assertTeacherHasNoRestrictedGroups(caller.id, courseId)
        }

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
 *  Assert that teacher has access to course group.
 */
fun AccessChecksBuilder.courseGroupAccessible(courseId: Long, groupId: Long) = add { caller: EasyUser ->

    when {
        caller.isAdmin() -> {}

        caller.isTeacher() -> assertTeacherHasAccessToCourseGroup(caller.id, courseId, groupId)

        else -> throw ForbiddenException("Role not allowed", ReqError.ROLE_NOT_ALLOWED)
    }
}

/**
 *  Assert that student has access to course.
 */
fun AccessChecksBuilder.studentOnCourse(courseId: Long) = add { caller: EasyUser ->
    assertStudentHasAccessToCourse(caller.id, courseId)
}

/**
 * Can access this course (restricted groups allowed) and this exercise is on that course (can be hidden).
 */
fun AccessChecksBuilder.exerciseViaCourse(exerciseId: Long, courseId: Long) = add { caller: EasyUser ->
    when {
        caller.isAdmin() -> {}

        caller.isTeacher() -> {
            assertTeacherCanAccessCourse(caller.id, courseId)
            assertExerciseIsOnCourse(exerciseId, courseId, false)
        }

        else -> throw ForbiddenException("Role not allowed", ReqError.ROLE_NOT_ALLOWED)
    }
}

fun assertCourseExerciseIsOnCourse(courseExId: Long, courseId: Long, requireStudentVisible: Boolean = true) {
    if (!isCourseExerciseOnCourse(courseExId, courseId, requireStudentVisible)) {
        throw InvalidRequestException(
            "Course exercise $courseExId not found on course $courseId " + if (requireStudentVisible) "or it is hidden" else "",
            ReqError.ENTITY_WITH_ID_NOT_FOUND
        )
    }
}

fun isExerciseOnCourse(exerciseId: Long, courseId: Long, requireStudentVisible: Boolean): Boolean = transaction {
    val query = CourseExercise.select {
        CourseExercise.course eq courseId and (CourseExercise.exercise eq exerciseId)
    }
    if (requireStudentVisible) {
        query.andWhere {
            CourseExercise.studentVisibleFrom.isNotNull() and CourseExercise.studentVisibleFrom.lessEq(DateTime.now())
        }
    }
    query.count() > 0
}

fun canTeacherAccessCourse(teacherId: String, courseId: Long): Boolean = transaction {
    TeacherCourseAccess.select {
        TeacherCourseAccess.teacher eq teacherId and (TeacherCourseAccess.course eq courseId)
    }.count() > 0
}

fun canStudentAccessCourse(studentId: String, courseId: Long): Boolean = transaction {
    StudentCourseAccess.select {
        StudentCourseAccess.student eq studentId and (StudentCourseAccess.course eq courseId)
    }.count() > 0
}


fun canTeacherOrAdminAccessCourseGroup(user: EasyUser, courseId: Long, groupId: Long): Boolean =
    when {
        user.isAdmin() -> true
        user.isTeacher() -> canTeacherAccessCourseGroup(user.id, courseId, groupId)
        else -> {
            log.warn { "User ${user.id} is not admin or teacher" }
            false
        }
    }


private fun canTeacherAccessCourseGroup(userId: String, courseId: Long, groupId: Long): Boolean = transaction {
    val restrictedGroups = getTeacherRestrictedCourseGroups(userId, courseId)
    restrictedGroups.isEmpty() || restrictedGroups.contains(groupId)
}

private fun getTeacherRestrictedCourseGroups(teacherId: String, courseId: Long): List<Long> = transaction {
    TeacherCourseGroup.select {
        TeacherCourseGroup.course eq courseId and (TeacherCourseGroup.teacher eq teacherId)
    }.map {
        it[TeacherCourseGroup.courseGroup].value
    }
}

private fun isCourseExerciseOnCourse(courseExId: Long, courseId: Long, requireStudentVisible: Boolean): Boolean =
    transaction {
        val query = CourseExercise.select {
            CourseExercise.course eq courseId and (CourseExercise.id eq courseExId)
        }
        if (requireStudentVisible) {
            query.andWhere {
                CourseExercise.studentVisibleFrom.isNotNull() and CourseExercise.studentVisibleFrom.lessEq(DateTime.now())
            }
        }
        query.count() > 0
    }

private fun assertTeacherHasAccessToCourseGroup(userId: String, courseId: Long, groupId: Long) {
    if (!canTeacherAccessCourseGroup(userId, courseId, groupId)) {
        throw ForbiddenException(
            "Teacher or admin $userId does not have access to group $groupId on course $courseId",
            ReqError.NO_GROUP_ACCESS
        )
    }
}

private fun assertTeacherCanAccessCourse(teacherId: String, courseId: Long) {
    if (!canTeacherAccessCourse(teacherId, courseId)) {
        throw ForbiddenException(
            "Teacher $teacherId does not have access to course $courseId", ReqError.NO_COURSE_ACCESS
        )
    }
}

private fun assertTeacherHasNoRestrictedGroups(teacherId: String, courseId: Long) {
    if (getTeacherRestrictedCourseGroups(teacherId, courseId).isNotEmpty()) {
        throw ForbiddenException(
            "Teacher $teacherId has restricted groups on course $courseId", ReqError.HAS_RESTRICTED_GROUPS
        )
    }
}

private fun assertExerciseIsOnCourse(exerciseId: Long, courseId: Long, requireStudentVisible: Boolean) {
    if (!isExerciseOnCourse(exerciseId, courseId, requireStudentVisible)) {
        throw ForbiddenException(
            "Exercise $exerciseId not found on course $courseId " + if (requireStudentVisible) "or it is hidden" else "",
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