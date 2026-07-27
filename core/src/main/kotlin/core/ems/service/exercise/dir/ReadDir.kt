package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.annotation.JsonSerialize
import core.conf.security.EasyUser
import core.db.*
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.ems.service.getAccountDirAccessLevel
import core.ems.service.getDir
import core.ems.service.idToLongOrInvalidReq
import core.util.DateTimeSerializer
import core.util.maxOfOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadDirController {
    private val log = KotlinLogging.logger {}

    data class Resp(
        @get:JsonProperty("current_dir") val currentDir: DirResp?, // null for root dir
        @get:JsonProperty("child_dirs") val childDirs: List<DirResp>,
        @get:JsonProperty("child_exercises") val childExercises: List<ExerciseResp>,
    )

    data class ExerciseResp(
        @get:JsonProperty("exercise_id") val exerciseId: String,
        @get:JsonProperty("dir_id") val implicitDirId: String,
        @get:JsonProperty("title") val title: String,
        @get:JsonProperty("effective_access") val effectiveAccess: DirAccessLevel,
        @get:JsonProperty("is_shared") val isShared: Boolean,
        @get:JsonProperty("grader_type") val graderType: GraderType,
        @get:JsonProperty("courses_count") val coursesCount: Int,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonProperty("created_by") val createdBy: String,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("modified_at") val modifiedAt: DateTime,
        @get:JsonProperty("modified_by") val modifiedBy: String,
    )

    data class DirResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("effective_access") val effectiveAccess: DirAccessLevel,
        @get:JsonProperty("is_shared") val isShared: Boolean,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("created_at") val createdAt: DateTime,
        @get:JsonSerialize(using = DateTimeSerializer::class)
        @get:JsonProperty("modified_at") val modifiedAt: DateTime,
    )


    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/lib/dirs/{dirId}")
    fun controller(
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ): Resp {

        log.info { "Read dir $dirIdString by ${caller.id}" }

        val dirId = if (dirIdString.equals("root", true)) null else dirIdString.idToLongOrInvalidReq()
        caller.assertAccess { if (dirId != null) libraryDir(dirId, DirAccessLevel.P) }

        return selectDir(caller, dirId)
    }


    private data class PotentialDirAccess(
        val id: Long, val name: String, val directAccess: DirAccessLevel?,
        val isImplicit: Boolean, val createdAt: DateTime, val modifiedAt: DateTime
    )

    private data class DirAccess(
        val id: Long, val name: String, val access: DirAccessLevel,
        val isImplicit: Boolean, val createdAt: DateTime, val modifiedAt: DateTime
    )

    private data class DirExercise(
        val id: String, val title: String, val createdBy: String, val modifiedBy: String, val graderType: GraderType,
        val createdAt: DateTime, val modifiedAt: DateTime, val usedOnCourse: Boolean
    )

    private fun selectDir(caller: EasyUser, dirId: Long?): Resp {
        // Can be null only if this dir is root
        val currentDirAccess = if (dirId != null) {
            getAccountDirAccessLevel(caller, dirId)
                ?: throw IllegalStateException("User ${caller.id} reading dir $dirId but has no access to it")
        } else null

        return transaction {
            // Get current dir
            val currentDir = selectThisDir(dirId, currentDirAccess)

            // If this dir is root or only has P, then need to return only children with at least P
            val dirs = when {
                caller.isAdmin() -> selectAllDirsForAdmin(dirId)
                // If caller is not admin return accessibleDirs
                else -> {
                    // Get child dirs
                    val potentialDirs: List<PotentialDirAccess> = getPotentialDirs(dirId, caller)

                    if (currentDirAccess == null || currentDirAccess == DirAccessLevel.P) {
                        potentialDirs.filter {
                            it.directAccess != null
                        }
                    } else {
                        potentialDirs
                    }.map {
                        val effectiveAccess = maxOfOrNull(currentDirAccess, it.directAccess)
                            ?: throw IllegalStateException("User ${caller.id} listing child dir ${it.id} but has no access")

                        DirAccess(
                            it.id,
                            it.name,
                            effectiveAccess,
                            it.isImplicit,
                            it.createdAt,
                            it.modifiedAt
                        )
                    }.also { log.trace { "accessible dirs: $it" } }
                }
            }


            // Extract out implicit dirs and their exercises
            val (implicitDirs, explicitDirs) =
                dirs.partition { it.isImplicit }


            val childDirs = explicitDirs.map {
                DirResp(
                    it.id.toString(),
                    it.name,
                    it.access,
                    // If current dir is shared, then children are also shared
                    if (currentDir?.isShared == true) true else isDirDirectlyShared(it.id),
                    it.createdAt,
                    it.modifiedAt
                )
            }

            val exerciseIds = implicitDirs.map { it.name.toLong() }

            val childExercises = (Exercise innerJoin ExerciseVer leftJoin CourseExercise)
                .select(
                    Exercise.id, Exercise.createdAt, ExerciseVer.title, ExerciseVer.graderType,
                    ExerciseVer.validFrom, CourseExercise.id, Exercise.owner, ExerciseVer.author
                )
                .where {
                    Exercise.id inList exerciseIds and
                            ExerciseVer.validTo.isNull()
                }.map {
                    @Suppress("SENSELESS_COMPARISON") // Nullability fails for left join
                    DirExercise(
                        it[Exercise.id].value.toString(),
                        it[ExerciseVer.title],
                        it[Exercise.owner].value,
                        it[ExerciseVer.author].value,
                        it[ExerciseVer.graderType],
                        it[Exercise.createdAt],
                        it[ExerciseVer.validFrom],
                        it[CourseExercise.id] != null
                    )
                }
                .also { log.trace { "ungrouped exercises: $it" } }
                // Due to left join:
                // If ex.usedOnCourse == false then there's always 1 occurrence
                // If ex.usedOnCourse == true then there's 1 or more occurrences, each representing one course
                .groupingBy { it }.eachCount()
                .also { log.trace { "grouped exercises: $it" } }
                .map { (ex, courseCount) ->
                    val dir = implicitDirs.first { it.name == ex.id }
                    ExerciseResp(
                        ex.id,
                        dir.id.toString(),
                        ex.title,
                        dir.access,
                        // If current dir is shared, then children are also shared
                        if (currentDir?.isShared == true) true else isDirDirectlyShared(dir.id),
                        ex.graderType,
                        if (ex.usedOnCourse) courseCount else 0,
                        ex.createdAt,
                        ex.createdBy,
                        ex.modifiedAt,
                        ex.modifiedBy
                    )
                }

            Resp(currentDir, childDirs, childExercises)
        }
    }

    private fun getPotentialDirs(dirId: Long?, caller: EasyUser): List<PotentialDirAccess> {
        return Dir.leftJoin(
            (GroupDirAccess innerJoin Group innerJoin AccountGroup),
            onColumn = { Dir.id },
            otherColumn = { GroupDirAccess.dir },
            additionalConstraint = { AccountGroup.account.eq(caller.id) }
        ).select(
            Dir.id, Dir.name, Dir.isImplicit, Dir.anyAccess, Dir.createdAt, Dir.modifiedAt,
            GroupDirAccess.level
        )
            .where { //and (AccountGroup.account eq caller.id or AccountGroup.account.isNull())
                Dir.parentDir eq dirId //and (AccountGroup.account eq caller.id or AccountGroup.account.isNull())
            }.map {
                PotentialDirAccess(
                    it[Dir.id].value,
                    it[Dir.name],
                    maxOfOrNull(it[GroupDirAccess.level], it[Dir.anyAccess]),
                    it[Dir.isImplicit],
                    it[Dir.createdAt],
                    it[Dir.modifiedAt],
                )
            }
            .also { log.trace { "potential accesses: $it" } }
            .groupBy { it.id }
            .also { log.trace { "grouped accesses: $it" } }
            .map { (_, accesses) ->
                accesses.reduce { best, current ->
                    val bestAccess = best.directAccess
                    val currentAccess = current.directAccess
                    when {
                        currentAccess == null -> best
                        bestAccess == null -> current
                        currentAccess > bestAccess -> current
                        else -> best
                    }
                }
            }.also { log.trace { "best accesses: $it" } }
    }

    private fun isDirDirectlyShared(dirId: Long): Boolean {
        data class DirectAccess(val isGroupImplicit: Boolean?, val anyAccess: DirAccessLevel?)

        // Get direct accesses (>= PR) and anyAccess for this dir in one query
        // leftJoin because there might be 0 accesses on it
        val directAccesses = Dir.leftJoin(
            (GroupDirAccess innerJoin Group), { Dir.id }, { GroupDirAccess.dir },
            { GroupDirAccess.level.greaterEq(DirAccessLevel.PR) })
            .select(Dir.anyAccess, Group.isImplicit)
            .where { Dir.id.eq(dirId) }
            .map {
                DirectAccess(
                    it[Group.isImplicit], // could be null due to leftJoin
                    it[Dir.anyAccess],
                )
            }

        // All rows contain the same anyAccess field
        val anyAccessible = directAccesses.firstOrNull()?.anyAccess?.let { it >= DirAccessLevel.PR } ?: false

        val hasExplicitGroupAccess = directAccesses.any { it.isGroupImplicit == false }

        // (anyAccess >= R) || (number of >= R accesses on item > 1) || (1 access on item is for an explicit group)
        return anyAccessible || directAccesses.count() > 1 || hasExplicitGroupAccess
    }

    private fun isDirSharedRec(dirId: Long?): Boolean {
        if (dirId == null) {
            return false
        }
        return isDirDirectlyShared(dirId) || isDirSharedRec(getDir(dirId)?.parentDir)
    }

    private fun selectThisDir(dirId: Long?, currentDirAccess: DirAccessLevel?): DirResp? {
        return if (dirId != null) {
            Dir.select(Dir.id, Dir.name, Dir.createdAt, Dir.modifiedAt)
                .where { Dir.id eq dirId }.map {
                    DirResp(
                        it[Dir.id].value.toString(),
                        it[Dir.name],
                        currentDirAccess!!, // not null if this is not root dir
                        isDirSharedRec(dirId),
                        it[Dir.createdAt],
                        it[Dir.modifiedAt],
                    )
                }.single()
        } else null
    }

    private fun selectAllDirsForAdmin(dirId: Long?): List<DirAccess> {
        return Dir.select(
            Dir.id, Dir.name, Dir.isImplicit, Dir.anyAccess, Dir.createdAt, Dir.modifiedAt
        ).where {
            Dir.parentDir eq dirId
        }.map {
            DirAccess(
                it[Dir.id].value,
                it[Dir.name],
                DirAccessLevel.PRAWM,
                it[Dir.isImplicit],
                it[Dir.createdAt],
                it[Dir.modifiedAt],
            )
        }
    }
}
