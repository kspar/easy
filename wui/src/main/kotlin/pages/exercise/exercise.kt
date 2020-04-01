package pages.exercise

import DateSerializer
import blankToNull
import components.BreadcrumbsComp
import components.CardTabsComp
import components.Crumb
import doInPromise
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import spa.Component
import successMessage
import tmRender
import kotlin.js.Date
import kotlin.js.Promise


@Serializable
data class ExerciseDTO(
        var is_public: Boolean,
        var grader_type: GraderType,
        var title: String,
        var text_adoc: String? = null,
        var grading_script: String? = null,
        var container_image: String? = null,
        var max_time_sec: Int? = null,
        var max_mem_mb: Int? = null,
        var assets: List<AssetDTO>? = null,
        var executors: List<ExecutorDTO>? = null,
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
        val owner_id: String,
        @Serializable(with = DateSerializer::class)
        val last_modified: Date,
        val last_modified_by_id: String,
        val text_html: String? = null,
        val on_courses: List<OnCourseDTO>
)

@Serializable
data class AssetDTO(
        val file_name: String,
        val file_content: String
)

@Serializable
data class ExecutorDTO(
        val id: String,
        val name: String
)

@Serializable
data class OnCourseDTO(
        val id: String,
        val title: String,
        val course_exercise_id: String,
        val course_exercise_title_alias: String?
)

enum class GraderType {
    AUTO, TEACHER
}


class ExerciseRootComp(
        private val exerciseId: String,
        private val preselectedTabId: String?,
        private val onActivateTab: (String) -> Unit,
        dstId: String
) : Component(null, dstId) {

    companion object {
        private const val EXERCISE_TAB_ID = "exercise"
        private const val AA_TAB_ID = "autoassess"
        private const val TESTING_TAB_ID = "testing"
    }

    private lateinit var crumbs: BreadcrumbsComp
    private lateinit var tabs: CardTabsComp


    override val children: List<Component>
        get() = listOf(crumbs, tabs)

    override fun create(): Promise<*> = doInPromise {
        val exercise = fetchEms("/exercises/$exerciseId", ReqMethod.GET,
                successChecker = { http200 }).await()
                .parseTo(ExerciseDTO.serializer()).await()

        crumbs = BreadcrumbsComp(listOf(Crumb.exercises, Crumb(exercise.title)), this)
        tabs = CardTabsComp(this, onActivateTab)

        val tabsList = mutableListOf<CardTabsComp.Tab>()

        tabsList.add(CardTabsComp.Tab(EXERCISE_TAB_ID, "Ülesanne", ExerciseTabComp(exercise, ::saveExercise, tabs), preselectedTabId == EXERCISE_TAB_ID))

        if (exercise.grader_type == GraderType.AUTO) {
            tabsList.add(CardTabsComp.Tab(AA_TAB_ID, "Automaatkontroll", AutoAssessmentTabComp(exercise, ::saveExercise, this.tabs), preselectedTabId == AA_TAB_ID))
            tabsList.add(CardTabsComp.Tab(TESTING_TAB_ID, "Katsetamine", TestingTabComp(exerciseId, this.tabs), preselectedTabId == TESTING_TAB_ID))
        }

        tabs.setTabs(tabsList)
    }

    override fun render(): String = tmRender("t-c-exercise",
            "crumbsDstId" to crumbs.dstId,
            "cardDstId" to tabs.dstId
    )

    private suspend fun saveExercise(exercise: ExerciseDTO) {
        val body = exercise.let {
            mapOf(
                    "title" to it.title,
                    "text_adoc" to it.text_adoc,
                    "public" to it.is_public,
                    "grader_type" to it.grader_type.name,
                    "grading_script" to it.grading_script,
                    "container_image" to it.container_image,
                    "max_time_sec" to it.max_time_sec,
                    "max_mem_mb" to it.max_mem_mb,
                    "assets" to it.assets?.map { mapOf("file_name" to it.file_name, "file_content" to it.file_content) },
                    "executors" to it.executors?.map { mapOf("executor_id" to it.id) }
            )
        }
        fetchEms("/exercises/$exerciseId", ReqMethod.PUT, body, successChecker = { http200 }).await()
        successMessage { "Ülesanne salvestatud" }
        createAndBuild().await()
    }
}
