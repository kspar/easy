package core.aas

import core.db.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


data class AutoExerciseDetails(
    val gradingScript: String,
    val containerImage: String,
    val maxTime: Int,
    val maxMem: Int,
    val assets: List<Pair<String, String>>,
    val executors: List<AutoExerciseExecutorBasic>
)

data class AutoExerciseExecutorBasic(
    val id: Long,
    val name: String
)


fun selectAutoExercise(autoExerciseId: EntityID<Long>): AutoExerciseDetails {
    return transaction {

        val assets =
            Asset.select { Asset.autoExercise eq autoExerciseId }
                .map { it[Asset.fileName] to it[Asset.fileContent] }

        val executors =
            (AutoExercise innerJoin ContainerImage innerJoin ExecutorContainerImage innerJoin Executor)
                .select { AutoExercise.id eq autoExerciseId }
                .map {
                    AutoExerciseExecutorBasic(
                        it[Executor.id].value,
                        it[Executor.name]
                    )
                }

        AutoExercise.select {
            AutoExercise.id eq autoExerciseId
        }.map {
            AutoExerciseDetails(
                it[AutoExercise.gradingScript],
                it[AutoExercise.containerImage].value,
                it[AutoExercise.maxTime],
                it[AutoExercise.maxMem],
                assets,
                executors
            )
        }.single()
    }
}