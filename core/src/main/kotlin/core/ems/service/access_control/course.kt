package core.ems.service.access_control

import core.conf.security.EasyUser
import core.ems.service.canTeacherAccessCourse
import core.ems.service.isExerciseOnCourse
import core.ems.service.teacherHasRestrictedGroupsOnCourse
import core.exception.ForbiddenException
import core.exception.ReqError


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