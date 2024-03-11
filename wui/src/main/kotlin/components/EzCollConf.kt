package components

import Key
import LocalStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import warn

object EzCollConf {
    @Serializable
    sealed interface EzCollFilterType

    @Serializable
    sealed interface EzCollSortType

    @Serializable
    data class TeacherSelectedGroupFilter(val id: String) : EzCollFilterType


    enum class ExerciseLibFilter : EzCollFilterType {
        ITEM_SHARED, ITEM_PRIVATE, GRADER_AUTO, GRADER_TEACHER,
    }

    enum class ExerciseLibSorter : EzCollSortType {
        NAME, LAST_MODIFIED, POPULARITY
    }

    enum class TeacherCourseExerciseSubmissionsFilter : EzCollFilterType {
        STATE_GRADED_AUTO, STATE_GRADED_TEACHER, STATE_UNGRADED, STATE_UNSUBMITTED
    }

    enum class TeacherCourseExerciseSubmissionsSorter : EzCollSortType {
        NAME, POINTS, TIME
    }

    enum class StudentCourseExercisesFilter : EzCollFilterType {
        STATE_NOT_DONE, STATE_DONE
    }

    enum class TeacherCourseExercisesFilter : EzCollFilterType {
        STATE_HAS_UNGRADED, STATE_VISIBLE, STATE_DEADLINE_FUTURE, STATE_DEADLINE_PAST
    }

    enum class ParticipantsFilter : EzCollFilterType {
        STATE_ACTIVE, STATE_PENDING
    }

    enum class ParticipantsSorter : EzCollSortType {
        GROUP_NAME, NAME
    }

    @Serializable
    data class UserConf(
        val filters: List<EzCollFilterType> = emptyList(),
        var globalGroupFilter: TeacherSelectedGroupFilter? = null,
        val sorter: EzCollSortType? = null,
        val sortOrderReversed: Boolean = false,
    ) {
        companion object {
            fun retrieve(localStoreKey: Key): UserConf {
                val str = LocalStore.get(localStoreKey)
                val conf = try {
                    if (str != null)
                        encoder.decodeFromString(serializer(), str)
                    else
                        UserConf()
                } catch (e: Exception) {
                    warn { e.message }
                    UserConf()
                }

                // populate from global course group filter
                val groupId = LocalStore.get(Key.TEACHER_SELECTED_GROUP)
                if (groupId != null)
                    conf.globalGroupFilter = TeacherSelectedGroupFilter(groupId)

                return conf
            }
        }

        fun store(localStoreKey: Key, hasCourseGroupFilter: Boolean = false) {
            // If the course group filter:
            // 1) exists, then set it globally
            // 2) *can exist* but did not exist (user removed it or wasn't selected before),
            //    then remove this selection from global store as well
            if (hasCourseGroupFilter)
                LocalStore.set(Key.TEACHER_SELECTED_GROUP, globalGroupFilter?.id)

            // This should never be stored here, only globally
            globalGroupFilter = null

            LocalStore.set(localStoreKey, encoder.encodeToString(serializer(), this))
        }
    }

    val encoder = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        useArrayPolymorphism = true
    }
}