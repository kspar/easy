package components

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import warn

object EzCollConf {
    @Serializable
    sealed interface EzCollFilterType

    @Serializable
    sealed interface EzCollSortType

    enum class ExerciseLibFilter : EzCollFilterType {
        ITEM_SHARED, ITEM_PRIVATE, GRADER_AUTO, GRADER_TEACHER,
    }

    enum class ExerciseLibSorter : EzCollSortType {
        NAME, LAST_MODIFIED, POPULARITY
    }

    @Serializable
    data class UserConf(
        val filters: List<EzCollFilterType> = emptyList(),
        val sorter: EzCollSortType? = null,
        val sortOrderReversed: Boolean = false,
    ) {
        companion object {
            fun decodeFromStringOrNull(str: String?) = try {
                str?.let { encoder.decodeFromString(serializer(), it) }
            } catch (e: Exception) {
                warn { e.message }
                null
            }
        }

        fun encodeToString() = encoder.encodeToString(serializer(), this)
    }

    val encoder = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        useArrayPolymorphism = true
    }
}