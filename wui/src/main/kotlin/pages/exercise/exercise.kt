package pages.exercise

import DateSerializer
import Icons
import Str
import blankToNull
import components.BreadcrumbsComp
import components.EditModeButtonsComp
import components.PageTabsComp
import dao.LibraryDirDAO
import debug
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.Title
import pages.exercise_library.createDirChainCrumbs
import pages.exercise_library.createPathChainSuffix
import pages.sidenav.Sidenav
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import successMessage
import tmRender
import kotlin.js.Date


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
    val dir_id: String,
    @Serializable(with = DateSerializer::class)
    val created_at: Date,
    val owner_id: String,
    @Serializable(with = DateSerializer::class)
    val last_modified: Date,
    val last_modified_by_id: String,
    val text_html: String? = null,
    val on_courses: List<OnCourseDTO>,
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
    private val setPathSuffix: (String) -> Unit,
    dstId: String
) : Component(null, dstId) {

    private val exerciseTabId = IdGenerator.nextId()
    private val autoassessTabId = IdGenerator.nextId()
    private val testingTabId = IdGenerator.nextId()

    private lateinit var crumbs: BreadcrumbsComp
    private lateinit var tabs: PageTabsComp
    private val editModeBtns = EditModeButtonsComp(::editModeChanged, ::saveExercise, ::wishesToCancel, parent = this)
    private lateinit var addToCourseModal: AddToCourseModalComp

    private lateinit var exerciseTab: ExerciseTabComp
    private lateinit var autoassessTab: AutoAssessmentTabComp

    override val children: List<Component>
        get() = listOf(crumbs, tabs, addToCourseModal)

    override fun create() = doInPromise {
        val exercise = fetchEms("/exercises/$exerciseId", ReqMethod.GET,
            successChecker = { http200 }).await()
            .parseTo(ExerciseDTO.serializer()).await()

        val parents = LibraryDirDAO.getDirParents(exercise.dir_id).await().reversed()

        setPathSuffix(createPathChainSuffix(parents.map { it.name } + exercise.title))

        crumbs = BreadcrumbsComp(createDirChainCrumbs(parents, exercise.title), this)
        tabs = PageTabsComp(
            buildList {
                add(
                    PageTabsComp.Tab("Ülesanne", preselected = true, id = exerciseTabId) {
                        ExerciseTabComp(exercise, it)
                            .also { exerciseTab = it }
                    }
                )

                add(
                    PageTabsComp.Tab("Automaatkontroll", id = autoassessTabId) {
                        val aaProps = if (exercise.grading_script != null) {
                            AutoAssessmentTabComp.AutoAssessProps(
                                exercise.grading_script!!,
                                exercise.assets!!.associate { it.file_name to it.file_content },
                                exercise.container_image!!, exercise.max_time_sec!!, exercise.max_mem_mb!!
                            )
                        } else null

                        AutoAssessmentTabComp(aaProps, ::validChanged, it)
                            .also { autoassessTab = it }
                    }
                )

                if (exercise.grader_type == GraderType.AUTO) {
                    add(
                        PageTabsComp.Tab("Katsetamine", id = testingTabId) {
                            TestingTabComp(exerciseId, it)
                        }
                    )
                }
            },
            trailerComp = editModeBtns,
            parent = this
        )
        addToCourseModal = AddToCourseModalComp(exerciseId, exercise.title, this)

        Title.update {
            it.pageTitle = exercise.title
            it.parentPageTitle = Str.exerciseLibrary()
        }

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                exercise.title, listOf(
                    Sidenav.Action(Icons.add, "Lisa kursusele") {
                        val r = addToCourseModal.openWithClosePromise().await()
                        if (r != null) {
                            recreate()
                        }
                    }
                )
            )
        )
    }

    override fun hasUnsavedChanges(): Boolean =
        exerciseTab.hasUnsavedChanges() ||
                autoassessTab.hasUnsavedChanges()

    override fun render(): String = tmRender(
        "t-c-exercise",
        "crumbsDstId" to crumbs.dstId,
        "tabsDstId" to tabs.dstId,
        "addToCourseModalDstId" to addToCourseModal.dstId,
    )


    private suspend fun saveExerciseOld(exercise: ExerciseDTO) {
        val body = exercise.let {
            mapOf(
                "title" to it.title,
                "text_adoc" to it.text_adoc.blankToNull(),
                "text_html" to it.text_html,
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

        recreate()
    }

    private suspend fun recreate() {
        val selectedTab = tabs.getSelectedTab()
//        val editorTabId = autoassessTab.getEditorActiveTabId()
        createAndBuild().await()
        tabs.setSelectedTab(selectedTab)
//        editorTabId?.let { autoassessTab.setEditorActiveTabId(editorTabId) }
    }

    private fun validChanged(isValid: Boolean) {
        // TODO: enable/disable edit mode save btn
        debug { "Exercise edit now valid: $isValid" }

    }

    private suspend fun editModeChanged(nowEditing: Boolean) {
        // indicator
        tabs.refreshIndicator()

        // exercise tab: title, text editor
        exerciseTab.setEditable(nowEditing)

        // aa tab: attrs, editor
        autoassessTab.setEditable(nowEditing)
    }

    private suspend fun saveExercise(): Boolean {
        // get attrs from exercise tab

        // get attrs from aa tab

        // save

        successMessage { "Ülesanne salvestatud" }
        recreate()
        return true
    }

    private suspend fun wishesToCancel() =
        // TODO: modal
        if (hasUnsavedChanges())
            window.confirm("Siin lehel on salvestamata muudatusi. Kas oled kindel, et soovid muutmise lõpetada ilma salvestamata?")
        else true
}
