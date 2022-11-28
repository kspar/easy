package dao

import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.exercise_library.DirAccess
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.encodeURIComponent
import kotlin.js.Promise

object LibraryDirDAO {

    @Serializable
    private data class NewDirDTO(val id: String)

    suspend fun createDir(name: String, parentDirId: String?): Promise<String> = doInPromise {
        debug { "Creating dir $name under dir $parentDirId" }
        fetchEms("/lib/dirs",
            ReqMethod.POST,
            mapOf(
                "name" to name,
                "parent_dir_id" to parentDirId,
            ),
            successChecker = { http200 }).await()
            .parseTo(NewDirDTO.serializer()).await().id
    }


    @Serializable
    private data class ParentsRespDTO(
        val parents: List<ParentsDTO>,
    )

    @Serializable
    data class ParentsDTO(
        val id: String,
        val name: String,
    )

    fun getDirParents(dirId: String): Promise<List<ParentsDTO>> = doInPromise {
        debug { "Getting parents for dir $dirId" }
        fetchEms("/lib/dirs/${dirId.encodeURIComponent()}/parents", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(ParentsRespDTO.serializer()).await().parents
    }


    @Serializable
    data class Accesses(
        val direct_any: AnyAccess?,
        val direct_accounts: List<AccountAccess>,
        val direct_groups: List<GroupAccess>,
        val inherited_any: AnyAccess?,
        val inherited_accounts: List<AccountAccess>,
        val inherited_groups: List<GroupAccess>,
    )

    @Serializable
    data class AnyAccess(
        val access: DirAccess,
        val inherited_from: InheritingDir? = null,
    )

    @Serializable
    data class AccountAccess(
        val username: String,
        val group_id: String,
        val given_name: String,
        val family_name: String,
        val email: String?,
        val access: DirAccess,
        val inherited_from: InheritingDir? = null,
    )

    @Serializable
    data class GroupAccess(
        val id: String,
        val name: String,
        val access: DirAccess,
        val inherited_from: InheritingDir? = null,
    )

    @Serializable
    data class InheritingDir(
        val id: String,
        val name: String,
    )

    fun getDirAccesses(dirId: String): Promise<Accesses> = doInPromise {
        debug { "Getting dir accesses for dir $dirId" }
        fetchEms("/lib/dirs/${dirId.encodeURIComponent()}/access", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Accesses.serializer()).await()
    }


    sealed interface Subject
    data class NewAccount(val email: String) : Subject
    data class Group(val id: String) : Subject

    fun putDirAccess(dirId: String, subject: Subject, level: DirAccess?) = doInPromise {
        debug { "Put dir access $level for dir $dirId to group/account $subject" }
        val body = buildMap {
            when (subject) {
                is Group ->
                    put("group_id", subject.id)
                is NewAccount ->
                    put("email", subject.email)
            }
            put("access_level", level?.name)
        }

        fetchEms(
            "/lib/dirs/${dirId.encodeURIComponent()}/access", ReqMethod.PUT, body, successChecker = { http200 }
        ).await()

        Unit
    }
}