package core.ems.service

import core.conf.security.EasyUser
import core.db.CourseExercise
import core.db.StudentCourseAccess
import core.db.TeacherCourseAccess
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

private val log = KotlinLogging.logger {}


fun assertUserHasAccessToCourse(user: EasyUser, courseId: Long) {
    if (!canUserAccessCourse(user, courseId)) {
        throw ForbiddenException("User ${user.id} does not have access to course $courseId")
    }
}

fun canUserAccessCourse(user: EasyUser, courseId: Long): Boolean =
        when {
            user.isAdmin() -> true
            user.isTeacher() -> canTeacherAccessCourse(user.id, courseId)
            user.isStudent() -> canStudentAccessCourse(user.id, courseId)
            else -> throw IllegalStateException("User has no mapped roles: $user")
        }

fun assertTeacherOrAdminHasAccessToCourse(user: EasyUser, courseId: Long) {
    if (!canTeacherOrAdminAccessCourse(user, courseId)) {
        throw ForbiddenException("Teacher or admin ${user.id} does not have access to course $courseId")
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

fun assertTeacherHasAccessToCourse(teacherId: String, courseId: Long) {
    if (!canTeacherAccessCourse(teacherId, courseId)) {
        throw ForbiddenException("Teacher $teacherId does not have access to course $courseId")
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
        throw ForbiddenException("Student $studentId does not have access to course $courseId")
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

