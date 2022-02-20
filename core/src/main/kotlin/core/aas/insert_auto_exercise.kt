package core.aas

import core.db.Asset
import core.db.AutoExercise
import core.db.ContainerImage
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction


/**
 * Check that parameters are suitable and insert a new automatic exercise.
 *
 * @throws InvalidRequestException if some required params are missing or incorrect
 */
fun insertAutoExercise(
    gradingScript: String?,
    containerImage: String?,
    maxTime: Int?,
    maxMem: Int?,
    assets: List<Pair<String, String>>?,
): EntityID<Long> {

    return transaction {

        if (gradingScript == null ||
            containerImage == null ||
            maxTime == null ||
            maxMem == null ||
            assets == null
        ) {
            throw InvalidRequestException("Parameters for autoassessable exercise are missing.")
        }

        if (ContainerImage.select { ContainerImage.id eq containerImage }.count() == 0L) {
            throw InvalidRequestException(
                "Container image '$containerImage' does not exist.",
                ReqError.ENTITY_WITH_ID_NOT_FOUND,
                "container_image" to containerImage
            )
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

        autoExerciseId
    }
}
