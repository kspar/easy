package core.ems.service

import core.conf.security.EasyUser
import core.db.*
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

private val log = KotlinLogging.logger {}


fun assertUserHasAccessToCourse(user: EasyUser, courseId: Long) {
    if (!canUserAccessCourse(user, courseId)) {
        throw ForbiddenException("User ${user.id} does not have access to course $courseId", ReqError.NO_COURSE_ACCESS)
    }
}

fun canUserAccessCourse(user: EasyUser, courseId: Long): Boolean {
    if (user.isAdmin()) {
        return true
    }
    if (user.isTeacher() && canTeacherAccessCourse(user.id, courseId)) {
        return true
    }
    return user.isStudent() && canStudentAccessCourse(user.id, courseId)
}

fun assertTeacherOrAdminHasAccessToCourse(user: EasyUser, courseId: Long) {
    if (!canTeacherOrAdminAccessCourse(user, courseId)) {
        throw ForbiddenException("Teacher or admin ${user.id} does not have access to course $courseId", ReqError.NO_COURSE_ACCESS)
    }
}

fun canTeacherOrAdminAccessCourse(user: EasyUser, courseId: Long): Boolean =
        when {
            user.isAdmin() -> true
            user.isTeacher() -> canTeacherAccessCourse(user.id, courseId)
            else -> {
                log.warn { "User ${user.id} is not admin or teacher" }
                false
            }
        }

fun assertTeacherOrAdminHasAccessToCourseGroup(user: EasyUser, courseId: Long, groupId: Long) {
    if (!canTeacherOrAdminAccessCourseGroup(user, courseId, groupId)) {
        throw ForbiddenException("Teacher or admin ${user.id} does not have access to group $groupId on course $courseId", ReqError.NO_GROUP_ACCESS)
    }
}

fun canTeacherOrAdminAccessCourseGroup(user: EasyUser, courseId: Long, groupId: Long): Boolean =
        when {
            user.isAdmin() -> true
            user.isTeacher() -> canTeacherAccessCourseGroup(user, courseId, groupId)
            else -> {
                log.warn { "User ${user.id} is not admin or teacher" }
                false
            }
        }

fun canTeacherAccessCourseGroup(user: EasyUser, courseId: Long, groupId: Long): Boolean {
    return transaction {
        val hasGroups = TeacherGroupAccess
                .select {
                    TeacherGroupAccess.course eq courseId and
                            (TeacherGroupAccess.teacher eq user.id)
                }.count() > 0

        if (!hasGroups) {
            true
        } else {
            TeacherGroupAccess.select {
                TeacherGroupAccess.course eq courseId and
                        (TeacherGroupAccess.teacher eq user.id) and
                        (TeacherGroupAccess.group eq groupId)
            }.count() > 0
        }
    }
}

fun assertTeacherHasAccessToCourse(teacherId: String, courseId: Long) {
    if (!canTeacherAccessCourse(teacherId, courseId)) {
        throw ForbiddenException("Teacher $teacherId does not have access to course $courseId", ReqError.NO_COURSE_ACCESS)
    }
}

fun canTeacherAccessCourse(teacherId: String, courseId: Long): Boolean {
    return transaction {
        TeacherCourseAccess
                .select {
                    TeacherCourseAccess.teacher eq teacherId and
                            (TeacherCourseAccess.course eq courseId)
                }
                .count() > 0
    }
}

fun assertStudentHasAccessToCourse(studentId: String, courseId: Long) {
    if (!canStudentAccessCourse(studentId, courseId)) {
        throw ForbiddenException("Student $studentId does not have access to course $courseId", ReqError.NO_COURSE_ACCESS)
    }
}

fun canStudentAccessCourse(studentId: String, courseId: Long): Boolean {
    return transaction {
        StudentCourseAccess
                .select {
                    StudentCourseAccess.student eq studentId and
                            (StudentCourseAccess.course eq courseId)
                }
                .count() > 0
    }
}

fun assertIsVisibleExerciseOnCourse(courseExId: Long, courseId: Long) {
    if (!isVisibleExerciseOnCourse(courseExId, courseId)) {
        throw InvalidRequestException("Exercise $courseExId not found on course $courseId or it is hidden")
    }
}

fun isVisibleExerciseOnCourse(courseExId: Long, courseId: Long): Boolean {
    return transaction {
        CourseExercise
                .select {
                    CourseExercise.course eq courseId and
                            (CourseExercise.id eq courseExId) and
                            (CourseExercise.studentVisible eq true)
                }
                .count() > 0
    }
}

fun canTeacherOrAdminHasAccessExercise(user: EasyUser, exerciseId: Long): Boolean {
    return when {
        user.isAdmin() -> true
        user.isTeacher() -> transaction {
            Exercise.select {
                Exercise.id eq exerciseId and (Exercise.owner eq user.id or Exercise.public)
            }.count() == 1
        }
        else -> {
            log.warn { "User ${user.id} is not admin or teacher" }
            false
        }
    }
}

fun assertTeacherOrAdminHasAccessToExercise(user: EasyUser, exerciseId: Long) {
    if (!canTeacherOrAdminHasAccessExercise(user, exerciseId)) {
        throw ForbiddenException("User ${user.id} does not have access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS)
    }
}

fun canTeacherOrAdminUpdateExercise(user: EasyUser, exerciseId: Long): Boolean {
    return when {
        user.isAdmin() -> true
        user.isTeacher() -> transaction {
            Exercise.select {
                Exercise.id eq exerciseId and (Exercise.owner eq user.id)
            }.count() == 1
        }
        else -> {
            log.warn { "User ${user.id} is not admin or teacher" }
            false
        }
    }
}

fun assertTeacherOrAdminCanUpdateExercise(user: EasyUser, exerciseId: Long) {
    if (!canTeacherOrAdminUpdateExercise(user, exerciseId)) {
        throw ForbiddenException("User ${user.id} does not have access to update exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS)
    }
}