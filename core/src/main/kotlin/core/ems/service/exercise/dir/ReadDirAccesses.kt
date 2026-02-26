package core.ems.service.exercise.dir

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import core.conf.security.EasyUser
import core.db.DirAccessLevel
import core.ems.service.access_control.assertAccess
import core.ems.service.access_control.libraryDir
import core.ems.service.assertDirExists
import core.ems.service.getAccountFromImplicitGroup
import core.ems.service.getDirectGroupDirAccesses
import core.ems.service.idToLongOrInvalidReq
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.security.access.annotation.Secured
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/v2")
class ReadDirAccesses {
    private val log = KotlinLogging.logger {}


    // Include all direct accesses and only effective inherited accesses
    data class Resp(
        @get:JsonProperty("direct_any") val directAnyAccess: AnyAccessResp?,
        @get:JsonProperty("direct_accounts") val directAccountAccesses: List<AccountAccessResp>,
        @get:JsonProperty("direct_groups") val directGroupAccesses: List<GroupAccessResp>,
        @get:JsonProperty("inherited_any") val inheritedAnyAccess: AnyAccessResp?,
        @get:JsonProperty("inherited_accounts") val inheritedAccountAccesses: List<AccountAccessResp>,
        @get:JsonProperty("inherited_groups") val inheritedGroupAccesses: List<GroupAccessResp>,
    )

    data class AnyAccessResp(
        @get:JsonProperty("access") val access: DirAccessLevel,
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("inherited_from") val inheritedFrom: InheritingDirResp?,
    )

    data class AccountAccessResp(
        @get:JsonProperty("username") val username: String,
        @get:JsonProperty("given_name") val givenName: String,
        @get:JsonProperty("family_name") val familyName: String,
        @get:JsonProperty("email") val email: String?,
        @get:JsonProperty("group_id") val implicitGroupId: String,
        @get:JsonProperty("access") val access: DirAccessLevel,
        // TODO: modified_at
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("inherited_from") val inheritedFrom: InheritingDirResp?,
    )

    data class GroupAccessResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String,
        @get:JsonProperty("access") val access: DirAccessLevel,
        // TODO: modified_at
        @get:JsonInclude(JsonInclude.Include.NON_NULL)
        @get:JsonProperty("inherited_from") val inheritedFrom: InheritingDirResp?,
    )

    data class InheritingDirResp(
        @get:JsonProperty("id") val id: String,
        @get:JsonProperty("name") val name: String,
    )

    @Secured("ROLE_TEACHER", "ROLE_ADMIN")
    @GetMapping("/lib/dirs/{dirId}/access")
    fun controller(
        @PathVariable("dirId") dirIdString: String,
        caller: EasyUser
    ): Resp {

        log.info { "Read accesses for dir $dirIdString by ${caller.id}" }

        val dirId = dirIdString.idToLongOrInvalidReq()

        caller.assertAccess {
            libraryDir(dirId, DirAccessLevel.PRAWM)
            assertDirExists(dirId, true)
        }

        return selectAccesses(caller, dirId)
    }


    private fun selectAccesses(caller: EasyUser, dirId: Long): Resp {
        data class AccessGroupDir(
            val groupId: Long, val groupName: String,
            val isImplicit: Boolean, val access: DirAccessLevel,
            val inheritingDirId: Long, val inheritingDirName: String
        )

        data class AccessAny(val access: DirAccessLevel, val inheritingDirId: Long, val inheritingDirName: String)

        // Return all direct accesses
        val directDir = getDirectGroupDirAccesses(dirId)

        // Return only effective inherited accesses
        // Group ID -> access
        val inheritedAccesses = mutableMapOf<Long, AccessGroupDir>()
        var inheritedAny: AccessAny? = null

        var parentDirId = directDir.parent
        while (parentDirId != null) {
            val dir = getDirectGroupDirAccesses(parentDirId)

            // Recompute best any access
            inheritedAny = when {
                dir.anyAccess == null -> inheritedAny
                dir.anyAccess != DirAccessLevel.P && (inheritedAny == null || dir.anyAccess > inheritedAny.access) ->
                    AccessAny(dir.anyAccess, dir.id, dir.name)

                else -> inheritedAny
            }

            // Recompute best group accesses
            dir.groupAccesses.filter { it.access != DirAccessLevel.P }.forEach { newGroupAccess ->
                val currentGroupAccess = inheritedAccesses[newGroupAccess.id]
                if (currentGroupAccess == null || newGroupAccess.access > currentGroupAccess.access) {
                    inheritedAccesses[newGroupAccess.id] = AccessGroupDir(
                        newGroupAccess.id,
                        newGroupAccess.name,
                        newGroupAccess.isImplicit,
                        newGroupAccess.access,
                        dir.id,
                        dir.name,
                    )
                }
            }

            parentDirId = dir.parent
        }


        // Separate account/group accesses
        val (directAccountsPart, directGroupsPart) =
            directDir.groupAccesses.partition {
                it.isImplicit
            }
        val (inheritedAccountsPart, inheritedGroupsPart) =
            inheritedAccesses.values.partition {
                it.isImplicit
            }

        // Allow only groups for which the caller has access
        // not sure if this makes sense - commented out for now, remove in the future
        val directGroupAccesses = directGroupsPart
//            .filter {
//            hasUserGroupAccess(caller, it.id, false)
//        }
            .map {
                GroupAccessResp(
                    it.id.toString(),
                    it.name,
                    it.access,
                    null
                )
            }
        val inheritedGroupAccesses = inheritedGroupsPart
//            .filter {
//            hasUserGroupAccess(caller, it.groupId, false)
//        }
            .map {
                GroupAccessResp(
                    it.groupId.toString(),
                    it.groupName,
                    it.access,
                    InheritingDirResp(it.inheritingDirId.toString(), it.inheritingDirName),
                )
            }

        // Resolve account accesses
        val directAccountAccesses = directAccountsPart.map {
            val account = getAccountFromImplicitGroup(it.id)
            AccountAccessResp(
                account.id,
                account.givenName,
                account.familyName,
                account.email,
                it.id.toString(),
                it.access,
                null
            )
        }
        val inheritedAccountAccesses = inheritedAccountsPart.map {
            val account = getAccountFromImplicitGroup(it.groupId)
            AccountAccessResp(
                account.id,
                account.givenName,
                account.familyName,
                account.email,
                it.groupId.toString(),
                it.access,
                InheritingDirResp(it.inheritingDirId.toString(), it.inheritingDirName),
            )
        }

        // Map any accesses
        val directAnyAccess = directDir.anyAccess?.let {
            AnyAccessResp(it, null)
        }
        val inheritedAnyAccess = inheritedAny?.let {
            AnyAccessResp(
                it.access,
                InheritingDirResp(it.inheritingDirId.toString(), it.inheritingDirName)
            )
        }

        return Resp(
            directAnyAccess, directAccountAccesses, directGroupAccesses,
            inheritedAnyAccess, inheritedAccountAccesses, inheritedGroupAccesses
        )
    }
}

