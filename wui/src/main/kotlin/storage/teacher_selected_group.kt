package storage

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import parseToOrCatch
import stringify


private val mapSerializer = MapSerializer(String.serializer(), String.serializer())


fun getSavedGroupId(courseId: String): String? = getAllSavedGroups()[courseId]

/**
 * TEACHER_SELECTED_GROUP -> Map<courseId: String, groupId: String>
 */
fun saveGroup(courseId: String, groupId: String?) {
    val allGroups = getAllSavedGroups().toMutableMap()
    if (groupId == null)
        allGroups.remove(courseId)
    else
        allGroups[courseId] = groupId

    if (allGroups.isEmpty())
        LocalStore.set(Key.TEACHER_SELECTED_GROUP, null)
    else
        LocalStore.set(Key.TEACHER_SELECTED_GROUP, mapSerializer.stringify(allGroups))
}

private fun getAllSavedGroups() =
    LocalStore.get(Key.TEACHER_SELECTED_GROUP)?.parseToOrCatch(mapSerializer) ?: emptyMap()
