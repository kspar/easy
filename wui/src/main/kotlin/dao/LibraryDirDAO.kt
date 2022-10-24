package dao

import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import org.w3c.fetch.Response
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

    fun createDirQuery(name: String, parentDirId: String?): Promise<Response> {
        debug { "Creating dir $name under dir $parentDirId" }
        return fetchEms("/lib/dirs",
            ReqMethod.POST,
            mapOf(
                "name" to name,
                "parent_dir_id" to parentDirId,
            ),
            successChecker = { http200 })
    }

    suspend fun createDir(name: String, parentDirId: String?): String =
        createDirQuery(name, parentDirId).await()
            .parseTo(NewDirDTO.serializer()).await().id


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
}