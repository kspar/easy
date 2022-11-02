package core.ems.service

import core.conf.security.EasyUser
import core.db.*
import core.exception.ForbiddenException
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.component1
import core.util.component2
import core.util.maxOfOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

private val log = KotlinLogging.logger {}


fun assertUserHasAccessToCourse(user: EasyUser, courseId: Long) {
    when {
        user.isAdmin() -> assertCourseExists(courseId)
        user.isTeacher() && canTeacherAccessCourse(user.id, courseId) -> return
        user.isStudent() && canStudentAccessCourse(user.id, courseId) -> return
        else -> throw ForbiddenException("User ${user.id} does not have access to course $courseId",
            ReqError.NO_COURSE_ACCESS)
    }
}

fun assertTeacherOrAdminHasAccessToCourse(user: EasyUser, courseId: Long) {
    when {
        user.isAdmin() -> assertCourseExists(courseId)
        user.isTeacher() -> assertTeacherHasAccessToCourse(user.id, courseId)
    }
}

fun assertTeacherOrAdminHasNoRestrictedGroupsOnCourse(user: EasyUser, courseId: Long) {
    when {
        user.isAdmin() -> return
        user.isTeacher() -> {
            if (teacherHasRestrictedGroupsOnCourse(user.id, courseId)) {
                throw ForbiddenException("Teacher ${user.id} has restricted groups on course $courseId",
                        ReqError.HAS_RESTRICTED_GROUPS)
            }
        }
    }
}

fun teacherHasRestrictedGroupsOnCourse(teacherId: String, courseId: Long): Boolean {
    return transaction {
        TeacherCourseGroup
                .select {
                    TeacherCourseGroup.course eq courseId and
                            (TeacherCourseGroup.teacher eq teacherId)
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

fun assertUnauthAccessToExercise(exerciseId: Long) {
    val unauthEnabled = transaction {
        Exercise.slice(Exercise.anonymousAutoassessEnabled)
            .select { Exercise.id.eq(exerciseId) }
            .map { it[Exercise.anonymousAutoassessEnabled] }
            .singleOrNull() ?: throw ForbiddenException(
            "No access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS
        )
    }

    if (!unauthEnabled) {
        throw ForbiddenException("No access to exercise $exerciseId", ReqError.NO_EXERCISE_ACCESS)
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

fun hasStudentPendingAccessToCourse(studentEmail: String, courseId: Long): Boolean {
    return transaction {
        StudentPendingAccess.select {
            StudentPendingAccess.email.eq(studentEmail) and
                    StudentPendingAccess.course.eq(courseId)
        }.count() > 0
    }
}

fun hasStudentMoodlePendingAccessToCourse(moodleUsername: String, courseId: Long): Boolean {
    return transaction {
        StudentMoodlePendingAccess.select {
            StudentMoodlePendingAccess.moodleUsername.eq(moodleUsername) and
                    StudentMoodlePendingAccess.course.eq(courseId)
        }.count() > 0
    }
}


fun assertCourseExerciseIsOnCourse(courseExId: Long, courseId: Long, requireStudentVisible: Boolean = true) {
    if (!isCourseExerciseOnCourse(courseExId, courseId, requireStudentVisible)) {
        throw InvalidRequestException(
            "Course exercise $courseExId not found on course $courseId " +
                    if (requireStudentVisible) "or it is hidden" else "",
            ReqError.ENTITY_WITH_ID_NOT_FOUND
        )
    }
}

fun isCourseExerciseOnCourse(courseExId: Long, courseId: Long, requireStudentVisible: Boolean): Boolean {
    return transaction {
        val query = CourseExercise.select {
            CourseExercise.course eq courseId and
                    (CourseExercise.id eq courseExId)
        }
        if (requireStudentVisible) {
            query.andWhere {
                CourseExercise.studentVisibleFrom.isNotNull() and
                        CourseExercise.studentVisibleFrom.lessEq(DateTime.now())
            }
        }
        query.count() > 0
    }
}

fun isExerciseOnCourse(exerciseId: Long, courseId: Long, requireStudentVisible: Boolean): Boolean {
    return transaction {
        val query = CourseExercise
            .select {
                CourseExercise.course eq courseId and
                        (CourseExercise.exercise eq exerciseId)
            }
        if (requireStudentVisible) {
            query.andWhere {
                CourseExercise.studentVisibleFrom.isNotNull() and
                        CourseExercise.studentVisibleFrom.lessEq(DateTime.now())
            }
        }
        query.count() > 0
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

fun hasAccountDirAccess(user: EasyUser, dirId: Long, level: DirAccessLevel): Boolean {
    return when {
        user.isAdmin() -> true
        else -> {
            val effectiveLevel = getAccountDirAccessLevel(user, dirId, level)
            log.trace { "effective level: $effectiveLevel" }
            return effectiveLevel != null && effectiveLevel >= level
        }
    }
}

/**
 * Get this user's access level to this dir. Accounts for both directly given accesses and accesses inherited from
 * parent directories. Also accounts for accesses given directly this user, as well as accesses given to any groups
 * that the user is in.
 * @param target - if this is anything less than PRAWM (full access), then the returned access level might not be
 * the real highest access level; this parameter is used for optimisation - if the target level is achieved then we can
 * return early, without calculating other inherited accesses (even though these might increase the access level)
 */
fun getAccountDirAccessLevel(
    user: EasyUser,
    dirId: Long,
    target: DirAccessLevel = DirAccessLevel.PRAWM
): DirAccessLevel? =
    if (user.isAdmin()) DirAccessLevel.PRAWM
    else getEffectiveDirAccessLevelRec(user.id, dirId, target, null, true)

private tailrec fun getEffectiveDirAccessLevelRec(
    userId: String, dirId: Long, target: DirAccessLevel,
    previousBestLevel: DirAccessLevel?, isDirect: Boolean
): DirAccessLevel? {
    log.trace { "dir: $dirId, previous: $previousBestLevel" }

    // Get best direct (non-inherited) access level for current dir, accounting for groups
    // This includes accesses given to the user directly because these are given to the user's implicit group
    val currentDirGroupLevel = getAccountDirectDirAccessLevel(userId, dirId)

    // Get parent dir id and current "any access" level
    val (parentDirId, currentAnyAccessLevel) = transaction {
        Dir.slice(Dir.parentDir, Dir.anyAccess)
            .select { Dir.id eq dirId }
            .map {
                it[Dir.parentDir] to it[Dir.anyAccess]
            }
            .firstOrNull()
    }

    // The best direct access level is either one that comes from group accesses or "any access"
    val initialDirectBestLevel = maxOfOrNull(currentDirGroupLevel, currentAnyAccessLevel)

    // P is not inherited - if current dir has P and it's not direct, then don't count it
    val directBestLevel = if (!isDirect && initialDirectBestLevel == DirAccessLevel.P) null else initialDirectBestLevel
    log.trace { "directBestLevel: $directBestLevel" }

    // The effective access level is either the direct best access or one that was previously found (lower in the tree)
    val bestLevel = maxOfOrNull(directBestLevel, previousBestLevel)
    log.trace { "bestLevel: $bestLevel" }

    return when {
        // Target level achieved, can return early
        bestLevel != null && bestLevel >= target -> bestLevel.also { log.trace { "finish, target $target achieved" } }
        // Finished search, return best access even though target was not achieved
        parentDirId == null -> bestLevel.also { log.trace { "finish, parent null" } }
        // Target is not achieved and parent exists, so can continue search
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

fun hasUserGroupAccess(user: EasyUser, groupId: Long, requireManager: Boolean): Boolean {
    return when {
        user.isAdmin() -> true
        else -> hasAccountGroupAccess(user.id, groupId, requireManager)
    }
}

private fun hasAccountGroupAccess(accountId: String, groupId: Long, requireManager: Boolean): Boolean {
    return transaction {
        val q = AccountGroup.select {
            AccountGroup.account eq accountId and (AccountGroup.group eq groupId)
        }
        if (requireManager) {
            q.andWhere { AccountGroup.isManager eq true }
        }
        q.count() >= 1L
    }
}