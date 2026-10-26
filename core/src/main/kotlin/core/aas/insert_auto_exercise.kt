package core.aas

import core.db.Asset
import core.db.AutoExercise
import core.db.ContainerImage
import core.exception.InvalidRequestException
import core.exception.ReqError
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction


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
): EntityID<Long> = transaction {

    // A request that leaves these out is the client's mistake, and an ordinary one: an API caller
    // creating an AUTO exercise by hand and not knowing it needs five more fields. This used to throw
    // with no code and the default notify, so the caller got `code: null` and no clue which field,
    // and ops got a system notification mail for every such attempt. Named by wire name, all at
    // once, so one retry is enough.
    val missing = listOfNotNull(
        "grading_script".takeIf { gradingScript == null },
        "container_image".takeIf { containerImage == null },
        "max_time_sec".takeIf { maxTime == null },
        "max_mem_mb".takeIf { maxMem == null },
        "assets".takeIf { assets == null },
    )
    if (gradingScript == null || containerImage == null || maxTime == null || maxMem == null || assets == null) {
        throw InvalidRequestException(
            "Parameters for autoassessable exercise are missing: ${missing.joinToString()}.",
            ReqError.INVALID_PARAMETER_VALUE,
            "missing" to missing.joinToString(","),
            notify = false
        )
    }

    if (ContainerImage.selectAll().where { ContainerImage.id eq containerImage }.count() == 0L) {
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
