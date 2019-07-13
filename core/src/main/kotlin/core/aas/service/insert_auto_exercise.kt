package core.aas.service

import core.db.Asset
import core.db.AutoExercise
import core.db.AutoExerciseExecutor
import core.db.Executor
import core.ems.service.idToLongOrInvalidReq
import core.exception.InvalidRequestException
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


/**
 * Check that parameters are suitable and insert a new automatic exercise.
 *
 * @throws InvalidRequestException if some required params are missing or incorrect
 */
fun insertAutoExercise(gradingScript: String?, containerImage: String?, maxTime: Int?, maxMem: Int?,
                       assets: List<Pair<String, String>>?, executors: List<Long>?): EntityID<Long> {

    return transaction {

        if (gradingScript == null ||
                containerImage == null ||
                maxTime == null ||
                maxMem == null ||
                assets == null ||
                executors == null) {

            throw InvalidRequestException("Parameters for autoassessable exercise are missing.")
        }

        if (executors.isEmpty()) {
            throw InvalidRequestException("Autoassessable exercise must have at least 1 executor")
        }

        val executorIds = executors.map {
            val executorId = EntityID(it, Executor)
            if (Executor.select { Executor.id eq executorId }.count() == 0) {
                throw InvalidRequestException("Executor $executorId does not exist")
            }
            executorId
        }

        val autoExerciseId = AutoExercise.insertAndGetId {
            it[AutoExercise.gradingScript] = gradingScript
            it[AutoExercise.containerImage] = containerImage
            it[AutoExercise.maxTime] = maxTime
            it[AutoExercise.maxMem] = maxMem
        }

        Asset.batchInsert(assets) {
            this[Asset.autoExercise] = autoExerciseId
            this[Asset.fileName] = it.first
            this[Asset.fileContent] = it.second
        }

        AutoExerciseExecutor.batchInsert(executorIds) {
            this[AutoExerciseExecutor.autoExercise] = autoExerciseId
            this[AutoExerciseExecutor.executor] = it
        }

        autoExerciseId
    }
}
