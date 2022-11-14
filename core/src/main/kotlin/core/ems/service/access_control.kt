package core.ems.service

import core.conf.security.EasyUser
import core.db.*
import core.exception.ForbiddenException
import core.exception.ReqError
import core.util.component1
import core.util.component2
import core.util.maxOfOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

private val log = KotlinLogging.logger {}


/**
 * Add given access level to given group for dir
 */
fun libraryDirAddAccess(dirId: Long, groupId: Long, level: DirAccessLevel) {
    transaction {
        //Add given access to given group G.
        GroupDirAccess.insertOrUpdate(
            listOf(GroupDirAccess.group, GroupDirAccess.dir),
            listOf(GroupDirAccess.group, GroupDirAccess.dir)
        ) {
            it[GroupDirAccess.group] = groupId
            it[GroupDirAccess.dir] = dirId
            it[GroupDirAccess.level] = level
            it[GroupDirAccess.createdAt] = DateTime.now()
        }

        // Look at parent dir D if it exists (if not, end).
        val parentDirId = Dir
            .slice(Dir.parentDir)
            .select { Dir.id eq dirId }
            .firstNotNullOfOrNull { it[Dir.parentDir]?.value } ?: return@transaction

        //  If G has at least P access to D, end.
        val parentDirAccessLevel = GroupDirAccess
            .slice(GroupDirAccess.level)
            .select {
                (GroupDirAccess.dir eq parentDirId) and (GroupDirAccess.group eq groupId)
            }.map { it[GroupDirAccess.level] }
            .firstOrNull()

        // Add P access for G to D -> repeat
        if (parentDirAccessLevel == null) {
            libraryDirAddAccess(parentDirId, groupId, DirAccessLevel.P)
        }
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