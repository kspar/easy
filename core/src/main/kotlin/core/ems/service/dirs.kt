package core.ems.service

import core.conf.security.EasyUser
import core.db.*
import core.exception.InvalidRequestException
import core.exception.ReqError
import core.util.component1
import core.util.component2
import core.util.maxOfOrNull
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime

private val log = KotlinLogging.logger {}


fun libraryDirRemoveAccess(dirId: Long, groupId: Long?) {
    // groupId == null -> any access
    transaction {
        val level = if (groupId != null)
            getAccessLevel(dirId, groupId)
        else
            getDir(dirId)?.anyAccess

        if (level == null || level == DirAccessLevel.P)
            return@transaction

        // if we have access to any child, replace with P and end, else remove access and continue
        if (groupId != null) {
            if (hasChildrenAccess(dirId, groupId)) {
                upsertGroupDirAccess(groupId, dirId, DirAccessLevel.P)
                return@transaction
            } else {
                removeAccess(dirId, groupId)
            }
        } else {
            if (hasChildrenAnyAccess(dirId)) {
                updateAnyDirAccess(dirId, DirAccessLevel.P)
                return@transaction
            } else {
                updateAnyDirAccess(dirId, null)
            }
        }

        // if we removed this access then we might have to remove P accesses up the chain
        getDir(dirId)?.parentDir?.let { parentDirId ->
            if (groupId != null)
                removeRootchainPassthrough(parentDirId, groupId)
            else
                removeRootchainPassthroughAny(parentDirId)
        }
    }
}

private fun removeRootchainPassthrough(dirId: Long, groupId: Long) {
    // if dir has P access and no children accesses, remove and continue up the chain, else end
    if (getAccessLevel(dirId, groupId) == DirAccessLevel.P && !hasChildrenAccess(dirId, groupId)) {
        removeAccess(dirId, groupId)

        getDir(dirId)?.parentDir?.let { parentDirId ->
            removeRootchainPassthrough(parentDirId, groupId)
        }
    }
}

private fun removeRootchainPassthroughAny(dirId: Long) {
    val dir = getDir(dirId)
    if (dir?.anyAccess == DirAccessLevel.P && !hasChildrenAnyAccess(dirId)) {
        updateAnyDirAccess(dirId, null)

        dir.parentDir?.let { parentDirId ->
            removeRootchainPassthroughAny(parentDirId)
        }
    }
}

private fun getAccessLevel(dirId: Long, groupId: Long): DirAccessLevel? = transaction {
    GroupDirAccess.select {
        GroupDirAccess.dir.eq(dirId) and GroupDirAccess.group.eq(groupId)
    }.map {
        it[GroupDirAccess.level]
    }.singleOrNull()
}

private fun hasChildrenAccess(dirId: Long, groupId: Long): Boolean = transaction {
    (Dir innerJoin GroupDirAccess).select {
        Dir.parentDir.eq(dirId) and
                GroupDirAccess.group.eq(groupId)
    }.count() > 0
}

private fun hasChildrenAnyAccess(dirId: Long): Boolean = transaction {
    Dir.select {
        Dir.parentDir.eq(dirId) and Dir.anyAccess.isNotNull()
    }.count() > 0
}

private fun removeAccess(dirId: Long, groupId: Long) = transaction {
    GroupDirAccess.deleteWhere {
        GroupDirAccess.dir.eq(dirId) and GroupDirAccess.group.eq(groupId)
    }
}


/**
 * Add or update access to given group (or any account if null) for dir
 */
fun libraryDirPutAccess(dirId: Long, groupId: Long?, level: DirAccessLevel) {
    // groupId == null -> any access
    transaction {
        val dir = getDir(dirId) ?: return@transaction

        // Allow PRA only for explicit dirs
        if (dir.isImplicit && level == DirAccessLevel.PRA) {
            return@transaction
        }

        // Add access
        if (groupId != null)
            upsertGroupDirAccess(groupId, dirId, level)
        else
            updateAnyDirAccess(dirId, level)

        // Look at parent dir if it exists
        val parentDirId = dir.parentDir ?: return@transaction

        val parentDirAccessLevel =
            if (groupId != null)
                getAccessLevel(parentDirId, groupId)
            else
                getDir(parentDirId)?.anyAccess

        // If we don't have at least P access to parent, add it up the chain
        if (parentDirAccessLevel == null) {
            libraryDirPutAccess(parentDirId, groupId, DirAccessLevel.P)
        }
    }
}


fun getImplicitDirFromExercise(exerciseId: Long): Long = transaction {
    Dir
        .slice(Dir.id)
        .select {
            Dir.name.eq(exerciseId.toString()) and Dir.isImplicit
        }.map {
            it[Dir.id]
        }
        .single().value
}

fun getExerciseFromImplicitDir(implicitDirId: Long): Long = transaction {
    TODO("Should it return exercise ID or more attrs like for groups?")
}

data class ExerciseDir(
    val id: Long,
    val name: String,
    val isImplicit: Boolean,
    val parentDir: Long?,
    val anyAccess: DirAccessLevel?,
    val createdAt: DateTime,
    val modifiedAt: DateTime,
)

fun getDir(dirId: Long): ExerciseDir? = transaction {
    Dir.select { Dir.id eq dirId }
        .map {
            ExerciseDir(
                it[Dir.id].value,
                it[Dir.name],
                it[Dir.isImplicit],
                it[Dir.parentDir]?.value,
                it[Dir.anyAccess],
                it[Dir.createdAt],
                it[Dir.modifiedAt],
            )
        }
        .singleOrNull()
}

fun assertDirExists(dirId: Long, allowImplicit: Boolean = false) {
    if (!dirExists(dirId, allowImplicit)) {
        val explicit = if (!allowImplicit) "explicit" else ""
        throw InvalidRequestException(
            "No $explicit dir with id $dirId",
            ReqError.ENTITY_WITH_ID_NOT_FOUND, "id" to dirId.toString()
        )
    }
}

fun dirExists(dirId: Long, allowImplicit: Boolean = false): Boolean {
    return transaction {
        val q = Dir.select {
            Dir.id eq dirId
        }
        if (!allowImplicit) {
            q.andWhere { Dir.isImplicit eq false }
        }

        q.count() == 1L
    }
}

fun assertDirIsEmpty(dirId: Long) = transaction {
    val childCount = Dir.select {
        Dir.parentDir eq dirId
    }.count()
    if (childCount > 0)
        throw InvalidRequestException(
            "Dir $dirId is not empty",
            ReqError.DIR_NOT_EMPTY, "id" to dirId.toString()
        )
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

fun getAccountDirectDirAccessLevel(userId: String, dirId: Long): DirAccessLevel? = transaction {
    (AccountGroup innerJoin Group innerJoin GroupDirAccess)
        .slice(GroupDirAccess.level)
        .select {
            AccountGroup.account eq userId and
                    (GroupDirAccess.dir eq dirId)
        }.maxOfOrNull {
            it[GroupDirAccess.level].also { log.trace { "has group access: $it" } }
        }
        .also { log.trace { "best group access: $it" } }
}

fun upsertGroupDirAccess(groupId: Long, dirId: Long, level: DirAccessLevel) = transaction {
    GroupDirAccess.insertOrUpdate(
        listOf(GroupDirAccess.group, GroupDirAccess.dir),
        listOf(GroupDirAccess.group, GroupDirAccess.dir)
    ) {
        it[group] = groupId
        it[dir] = dirId
        it[GroupDirAccess.level] = level
        it[createdAt] = DateTime.now()
    }
}

fun updateAnyDirAccess(dirId: Long, level: DirAccessLevel?) = transaction {
    Dir.update({ Dir.id.eq(dirId) }) {
        it[anyAccess] = level
        it[modifiedAt] = DateTime.now()
    }
}

data class AccessDir(
    val id: Long, val name: String, val parent: Long?,
    val anyAccess: DirAccessLevel?, val groupAccesses: List<AccessGroup>
)

data class AccessGroup(val id: Long, val name: String, val access: DirAccessLevel, val isImplicit: Boolean)

fun getDirectGroupDirAccesses(dirId: Long): AccessDir = transaction {
    val accesses = (GroupDirAccess innerJoin Group)
        .slice(Group.id, Group.name, GroupDirAccess.level, Group.isImplicit)
        .select {
            GroupDirAccess.dir eq dirId
        }.map {
            // Can only have one access for this dir per group, so don't need to aggregate
            AccessGroup(
                it[Group.id].value,
                it[Group.name],
                it[GroupDirAccess.level],
                it[Group.isImplicit],
            )
        }

    Dir.select {
        Dir.id eq dirId
    }.map {
        AccessDir(
            it[Dir.id].value,
            it[Dir.name],
            it[Dir.parentDir]?.value,
            it[Dir.anyAccess],
            accesses
        )
    }.single()
}


@Deprecated("For debugging only")
fun debugPrintDir(dirId: Long? = null) {
    log.warn { "For debugging only" }
    printDir(dirId, 0)
}

private fun printDir(dirId: Long? = null, level: Int = 0) {
    transaction {
        Dir.select {
            Dir.parentDir eq dirId
        }.map {
            val id = it[Dir.id].value
            val name = it[Dir.name]
            val anyAccess = if (it[Dir.anyAccess] != null) "(${it[Dir.anyAccess]})" else ""
            val implicit = if (it[Dir.isImplicit]) "EXR" else "DIR"

            data class GroupAccess(val groupId: Long, val groupName: String, val access: DirAccessLevel)

            val accesses = (GroupDirAccess innerJoin Group)
                .select {
                    GroupDirAccess.dir eq id
                }.map {
                    GroupAccess(
                        it[Group.id].value,
                        it[Group.name],
                        it[GroupDirAccess.level]
                    )
                }

            log.debug { "${" ".repeat(level * 4)}| $implicit: $name [$id] $anyAccess" }
            accesses.forEach {
                log.debug { "${" ".repeat(level * 4)}  Group ${it.groupName} [${it.groupId}] - ${it.access}" }
            }

            printDir(id, level + 1)
        }
    }
}