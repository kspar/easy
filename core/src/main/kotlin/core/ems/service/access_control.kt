package core.ems.service

import core.conf.security.EasyUser
import core.db.*
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.component1
import core.util.component2
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
        val hasGroups = teacherHasRestrictedGroupsOnCourse(user, courseId)
        if (!hasGroups) {
            true
        } else {
            TeacherCourseGroup.select {
                TeacherCourseGroup.course eq courseId and
                        (TeacherCourseGroup.teacher eq user.id) and
                        (TeacherCourseGroup.courseGroup eq groupId)
            }.count() > 0
        }
    }
}

fun assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(user: EasyUser, courseId: Long) {
    when {
        user.isAdmin() -> return
        user.isTeacher() -> {
            if (teacherHasRestrictedGroupsOnCourse(user, courseId)) {
                throw ForbiddenException("Teacher ${user.id} has restricted groups on course $courseId",
                        ReqError.HAS_RESTRICTED_GROUPS)
            }
        }
    }
}

fun teacherHasRestrictedGroupsOnCourse(user: EasyUser, courseId: Long): Boolean {
    return transaction {
        TeacherCourseGroup
                .select {
                    TeacherCourseGroup.course eq courseId and
                            (TeacherCourseGroup.teacher eq user.id)
                }.count() > 0
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
            }.count() == 1L
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
            }.count() == 1L
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

fun assertAccountHasDirAccess(user: EasyUser, dirId: Long, level: DirAccessLevel) {
    if (!hasAccountDirAccess(user, dirId, level)) {
        throw ForbiddenException("User ${user.id} does not have $level access to dir $dirId", ReqError.NO_DIR_ACCESS)
    }
}

fun hasAccountDirAccess(user: EasyUser, dirId: Long, level: DirAccessLevel): Boolean {
    return when {
        user.isAdmin() -> true
        else -> {
            val effectiveLevel = getAccountDirAccessLevel(user.id, dirId, level)
            log.trace { "effective level: $effectiveLevel" }
            return effectiveLevel != null && effectiveLevel >= level
        }
    }
}

fun getAccountDirAccessLevel(userId: String, dirId: Long, target: DirAccessLevel = DirAccessLevel.PRAWM): DirAccessLevel? =
        getEffectiveDirAccessLevelRec(userId, dirId, target, null, true)

private tailrec fun getEffectiveDirAccessLevelRec(userId: String, dirId: Long, target: DirAccessLevel,
                                                  previousBestLevel: DirAccessLevel?, isDirect: Boolean): DirAccessLevel? {
    log.trace { "dir: $dirId, previous: $previousBestLevel" }
    val currentDirGroupLevel = getAccountDirectDirAccessLevel(userId, dirId)
    val (parentDirId, currentAnyAccessLevel) = transaction {
        Dir.slice(Dir.parentDir, Dir.anyAccess)
                .select { Dir.id eq dirId }
                .map {
                    it[Dir.parentDir] to it[Dir.anyAccess]
                }.firstOrNull()
    }

    val initialDirectBestLevel = listOfNotNull(currentDirGroupLevel, currentAnyAccessLevel).maxOrNull()
    // P is not inherited - if current dir has P and it's not direct then don't count it
    val directBestLevel = if (!isDirect && initialDirectBestLevel == DirAccessLevel.P) null else initialDirectBestLevel
    log.trace { "directBestLevel: $directBestLevel" }
    val bestLevel = listOfNotNull(directBestLevel, previousBestLevel).maxOrNull()
    log.trace { "bestLevel: $bestLevel" }

    return when {
        // Desired level achieved
        bestLevel != null && bestLevel >= target -> bestLevel.also { log.trace { "finish, target $target achieved" } }
        parentDirId == null -> bestLevel.also { log.trace { "finish, parent null" } }
        else -> getEffectiveDirAccessLevelRec(userId, parentDirId.value, target, bestLevel, false)
    }
}

fun getAccountDirectDirAccessLevel(userId: String, dirId: Long): DirAccessLevel? {
    return transaction {
        (AccountGroup innerJoin Group innerJoin GroupDirAccess)
                .slice(GroupDirAccess.level)
                .select {
                    AccountGroup.account eq userId and
                            (GroupDirAccess.dir eq dirId)
                }.map {
                    it[GroupDirAccess.level].also { log.trace { "has group access: $it" } }
                }.maxOrNull()
                .also { log.trace { "best group access: $it" } }
    }
}
