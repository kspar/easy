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
    val missing = mapOf(
        "grading_script" to gradingScript,
        "container_image" to containerImage,
        "max_time_sec" to maxTime,
        "max_mem_mb" to maxMem,
        "assets" to assets,
    ).filterValues { it == null }.keys
    if (missing.isNotEmpty()) {
        throw InvalidRequestException(
            "Parameters for autoassessable exercise are missing: ${missing.joinToString()}.",
            ReqError.INVALID_PARAMETER_VALUE,
            "missing" to missing.joinToString(","),
            notify = false
        )
    }

    // Each is present, as the check above says; the compiler cannot read that from the map.
    val image = containerImage!!
    if (ContainerImage.selectAll().where { ContainerImage.id eq image }.count() == 0L) {
        throw InvalidRequestException(
            "Container image '$image' does not exist.",
            ReqError.ENTITY_WITH_ID_NOT_FOUND,
            "container_image" to image
        )
    }

    val autoExerciseId = AutoExercise.insertAndGetId {
        it[AutoExercise.gradingScript] = gradingScript!!
        it[AutoExercise.containerImage] = image
        it[AutoExercise.maxTime] = maxTime!!
        it[AutoExercise.maxMem] = maxMem!!
    }

    Asset.batchInsert(assets!!) {
        this[Asset.autoExercise] = autoExerciseId
        this[Asset.fileName] = it.first
        this[Asset.fileContent] = it.second
    }

    autoExerciseId
}
