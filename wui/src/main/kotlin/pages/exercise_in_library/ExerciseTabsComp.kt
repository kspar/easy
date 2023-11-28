package pages.exercise_in_library

import components.EditModeButtonsComp
import components.PageTabsComp
import components.ToastThing
import dao.ExerciseDAO
import kotlinx.browser.window
import kotlinx.coroutines.await
import pages.exercise_in_library.editor.AutoassessEditorComp
import pages.exercise_library.DirAccess
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import translation.Str


class ExerciseTabsComp(
    private val exerciseId: String,
    private val initialExercise: ExerciseDAO.Exercise,
    private val exerciseTextChanged: suspend (newHtml: String) -> Unit,
    private val exerciseTitleChanged: suspend (newTitle: String) -> Unit,
    private val exerciseSaved: suspend () -> Boolean,
    private val refreshExercise: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    companion object {
        private const val TAB_ID_EXERCISE = "LIB_EXERCISE_TAB_EXERCISE"
        private const val TAB_ID_AUTOASSESS = "LIB_EXERCISE_TAB_AUTOASSESS"
        private const val TAB_ID_TESTING = "LIB_EXERCISE_TAB_TESTING"
    }

    private lateinit var tabs: PageTabsComp

    // null if no write access
    private var editModeBtns: EditModeButtonsComp? = null

    private lateinit var exerciseTab: ExerciseTextEditTabComp
    private lateinit var autoassessTab: AutoAssessmentTabComp
    private var testingTab: TestingTabComp? = null

    override val children: List<Component>
        get() = listOf(tabs)

    override fun create() = doInPromise {

        tabs = PageTabsComp(
            type = PageTabsComp.Type.TOP_LEVEL,
            tabs = buildList {
                add(
                    PageTabsComp.Tab(Str.tabExercise, preselected = true, id = TAB_ID_EXERCISE) {
                        ExerciseTextEditTabComp(
                            initialExercise.title,
                            initialExercise.text_adoc.orEmpty(),
                            initialExercise.text_html.orEmpty(),
                            exerciseTextChanged,
                            exerciseTitleChanged,
                            ::validChanged,
                            it
                        )
                            .also { exerciseTab = it }
                    }
                )

                add(
                    PageTabsComp.Tab(
                        Str.tabAutoassess,
                        id = TAB_ID_AUTOASSESS,
                        onActivate = { autoassessTab.refreshTSLTabs() }) {
                        val aaProps = if (initialExercise.grading_script != null) {
                            AutoAssessmentTabComp.AutoAssessProps(
                                initialExercise.grading_script!!,
                                initialExercise.assets!!.associate { it.file_name to it.file_content },
                                initialExercise.container_image!!,
                                initialExercise.max_time_sec!!,
                                initialExercise.max_mem_mb!!
                            )
                        } else null

                        AutoAssessmentTabComp(aaProps, ::validChanged, it)
                            .also { autoassessTab = it }
                    }
                )

                if (initialExercise.grader_type == ExerciseDAO.GraderType.AUTO) {
                    add(
                        PageTabsComp.Tab(Str.tabTesting, id = TAB_ID_TESTING) {
                            TestingTabComp(exerciseId, it)
                                .also { testingTab = it }
                        }
                    )
                }
            },
            trailerComponent = if (initialExercise.effective_access >= DirAccess.PRAW) {
                {
                    EditModeButtonsComp(
                        { editModeChanged(it) },
                        exerciseSaved,
                        postModeChange = { tabs.refreshIndicator() },
                        canCancel = { wishesToCancel() },
                        parent = it
                    ).also { editModeBtns = it }
                }
            } else null,
            parent = this
        )
    }

    data class EditedExercise(
        val title: String, val textAdoc: String, val textHtml: String,
        val embedConfig: ExerciseDAO.EmbedConfig?, val editedAutoassess: AutoAssessmentTabComp.EditedAutoassess?
    )

    fun getEditedProps() = EditedExercise(
        exerciseTab.getCurrentTitle(),
        exerciseTab.getCurrentAdoc(),
        exerciseTab.getCurrentHtml(),
        if (initialExercise.is_anonymous_autoassess_enabled)
            ExerciseDAO.EmbedConfig(initialExercise.anonymous_autoassess_template)
        else null,
        autoassessTab.getEditedProps()
    )

    fun getSelectedTab() = tabs.getSelectedTab()
    fun setSelectedTab(tab: PageTabsComp.Tab) = tabs.setSelectedTab(tab)
    fun getEditorActiveView() = autoassessTab.getEditorActiveView()
    fun setEditorActiveView(view: AutoassessEditorComp.ActiveView?) = autoassessTab.setEditorActiveView(view)

    override fun hasUnsavedChanges(): Boolean =
        exerciseTab.hasUnsavedChanges() ||
                autoassessTab.hasUnsavedChanges()

    private fun validChanged(_notUsed: Boolean) {
        editModeBtns?.setSaveEnabled(exerciseTab.isValid() && autoassessTab.isValid())
    }

    private suspend fun editModeChanged(nowEditing: Boolean): Boolean {
        // Check if exercise has changed before editing
        if (nowEditing && hasExerciseChanged()) {
            ToastThing(Str.updatedInEditMsg)
            refreshExercise()
            return false
        }

        // exercise view
        exerciseTitleChanged(initialExercise.title)
        exerciseTextChanged(initialExercise.text_html.orEmpty())

        // exercise tab: title, text editor
        exerciseTab.setEditable(nowEditing)

        // aa tab: attrs, editor

        // Cannot restore editor view due to a timing bug:
        // setEditable here actually createAndBuilds itself when switching to not editing
        // and its descendant TSLRootComp recreates its children inside postChildrenBuilt using a promise without waiting it to resolve.
        // Therefore, setEditable here will actually return in a state where not all children have been built,
        // and will likely fail due to something being uninitialised.
//        val editorView = autoassessTab.getEditorActiveView()
        autoassessTab.setEditable(nowEditing)
//        autoassessTab.setEditorActiveView(editorView)

        // testing tab: warning
        testingTab?.setEditing(nowEditing)

        return true
    }

    private suspend fun hasExerciseChanged(): Boolean {
        val currentExercise = ExerciseDAO.getExercise(exerciseId).await()
        return currentExercise.title != initialExercise.title ||
                currentExercise.text_adoc != initialExercise.text_adoc ||
                currentExercise.text_html != initialExercise.text_html ||
                currentExercise.container_image != initialExercise.container_image ||
                currentExercise.grading_script != initialExercise.grading_script ||
                currentExercise.assets != initialExercise.assets ||
                currentExercise.max_time_sec != initialExercise.max_time_sec ||
                currentExercise.max_mem_mb != initialExercise.max_mem_mb
    }

    private suspend fun wishesToCancel() =
        if (hasUnsavedChanges())
            window.confirm(Str.exerciseUnsavedChangesMsg)
        else true
}
