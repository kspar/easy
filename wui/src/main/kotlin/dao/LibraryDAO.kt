package dao

import DateSerializer
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
import kotlin.js.Date
import kotlin.js.Promise

object LibraryDAO {

    @Serializable
    data class Lib(
        val current_dir: Dir?,
        val child_dirs: List<Dir>,
        val child_exercises: List<Exercise>,
    )

    @Serializable
    data class Dir(
        val id: String,
        val name: String,
        val effective_access: DirAccess,
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
        @Serializable(with = DateSerializer::class)
        val modified_at: Date,
    )

    @Serializable
    data class Exercise(
        val exercise_id: String,
        val dir_id: String,
        val title: String,
        val effective_access: DirAccess,
        val grader_type: ExerciseDAO.GraderType,
        val courses_count: Int,
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
        @Serializable(with = DateSerializer::class)
        val modified_at: Date,
    )

    fun getLibraryContent(parentDirId: String?): Promise<Lib> = doInPromise {
        debug { "Getting library content under dir $parentDirId" }
        val dirId = parentDirId?.encodeURIComponent() ?: "root"
        fetchEms("/lib/dirs/$dirId", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Lib.serializer()).await()
    }
}