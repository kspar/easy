package core.ems.service.access_control

import core.conf.security.EasyUser
import core.db.TeacherCourseGroup
import core.ems.service.assertStudentHasAccessToCourse
import core.ems.service.canTeacherAccessCourse
import core.ems.service.isExerciseOnCourse
import core.ems.service.teacherHasRestrictedGroupsOnCourse
import core.exception.ForbiddenException
import core.exception.ReqError
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


/**
 *  Has teacher-access to this course.
 */
fun AccessChecksBuilder.teacherOnCourse(courseId: Long, allowRestrictedGroups: Boolean) = add { caller: EasyUser ->
    when {
        caller.isAdmin() -> {}

        caller.isTeacher() -> {
            assertTeacherCanAccessCourse(caller.id, courseId)
            if (!allowRestrictedGroups)
                assertTeacherHasNoRestrictedGroups(caller.id, courseId)
        }

        else -> throw ForbiddenException("Role not allowed", ReqError.ROLE_NOT_ALLOWED)
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

fun assertTeacherHasAccessToCourseGroup(userId: String, courseId: Long, groupId: Long) {
    if (!canTeacherAccessCourseGroup(userId, courseId, groupId)) {
        throw ForbiddenException(
            "Teacher or admin $userId does not have access to group $groupId on course $courseId",
            ReqError.NO_GROUP_ACCESS
        )
    }
}

fun canTeacherAccessCourseGroup(userId: String, courseId: Long, groupId: Long): Boolean = transaction {
    val hasGroups = teacherHasRestrictedGroupsOnCourse(userId, courseId)
    if (!hasGroups) {
        true
    } else {
        TeacherCourseGroup.select {
            TeacherCourseGroup.course eq courseId and
                    (TeacherCourseGroup.teacher eq userId) and
                    (TeacherCourseGroup.courseGroup eq groupId)
        }.count() > 0
    }
}

fun assertTeacherCanAccessCourse(teacherId: String, courseId: Long) {
    if (!canTeacherAccessCourse(teacherId, courseId)) {
        throw ForbiddenException(
            "Teacher $teacherId does not have access to course $courseId",
            ReqError.NO_COURSE_ACCESS
        )
    }
}

fun assertTeacherHasNoRestrictedGroups(teacherId: String, courseId: Long) {
    if (teacherHasRestrictedGroupsOnCourse(teacherId, courseId)) {
        throw ForbiddenException(
            "Teacher $teacherId has restricted groups on course $courseId",
            ReqError.HAS_RESTRICTED_GROUPS
        )
    }
}

fun assertExerciseIsOnCourse(exerciseId: Long, courseId: Long, requireStudentVisible: Boolean) {
    if (!isExerciseOnCourse(exerciseId, courseId, requireStudentVisible)) {
        throw ForbiddenException(
            "Exercise $exerciseId not found on course $courseId " +
                    if (requireStudentVisible) "or it is hidden" else "",
            ReqError.ENTITY_WITH_ID_NOT_FOUND
        )
    }
}