package pages.exercise

import Icons
import Str
import components.BreadcrumbsComp
import components.EditModeButtonsComp
import components.PageTabsComp
import dao.ExerciseDAO
import dao.LibraryDirDAO
import kotlinx.browser.window
import kotlinx.coroutines.await
import pages.Title
import pages.exercise_library.createDirChainCrumbs
import pages.exercise_library.createPathChainSuffix
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import successMessage
import tmRender


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
    private lateinit var editModeBtns: EditModeButtonsComp
    private lateinit var addToCourseModal: AddToCourseModalComp

    private lateinit var exerciseTab: ExerciseTabComp
    private lateinit var autoassessTab: AutoAssessmentTabComp

    override val children: List<Component>
        get() = listOf(crumbs, tabs, addToCourseModal)

    override fun create() = doInPromise {
        val exercise = ExerciseDAO.getExercise(exerciseId).await()
        val parents = LibraryDirDAO.getDirParents(exercise.dir_id).await().reversed()

        setPathSuffix(createPathChainSuffix(parents.map { it.name } + exercise.title))

        crumbs = BreadcrumbsComp(createDirChainCrumbs(parents, exercise.title), this)

        // TODO: wrong parent
        editModeBtns = EditModeButtonsComp(::editModeChanged, ::saveExercise, ::wishesToCancel, parent = this)

        tabs = PageTabsComp(
            buildList {
                add(
                    PageTabsComp.Tab("Ülesanne", preselected = true, id = exerciseTabId) {
                        ExerciseTabComp(exercise, ::validChanged, it)
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

                if (exercise.grader_type == ExerciseDAO.GraderType.AUTO) {
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

    private suspend fun recreate() {
        val selectedTab = tabs.getSelectedTab()
        val editorView = autoassessTab.getEditorActiveView()

        createAndBuild().await()

        tabs.setSelectedTab(selectedTab)
        autoassessTab.setEditorActiveView(editorView)
    }

    private fun validChanged(_notUsed: Boolean) {
        editModeBtns.setSaveEnabled(exerciseTab.isValid() && autoassessTab.isValid())
    }

    private suspend fun editModeChanged(nowEditing: Boolean) {
        tabs.refreshIndicator()

        // exercise tab: title, text editor
        exerciseTab.setEditable(nowEditing)

        // aa tab: attrs, editor
        val editorView = autoassessTab.getEditorActiveView()
        autoassessTab.setEditable(nowEditing)
        autoassessTab.setEditorActiveView(editorView)
    }

    private suspend fun saveExercise(): Boolean {
        val exerciseProps = exerciseTab.getEditedProps()
        val autoevalProps = autoassessTab.getEditedProps()

        ExerciseDAO.updateExercise(
            exerciseId,
            ExerciseDAO.UpdatedExercise(
                exerciseProps.title,
                exerciseProps.textAdoc,
                exerciseProps.textHtml,
                if (autoevalProps != null)
                    ExerciseDAO.Autoeval(
                        autoevalProps.containerImage,
                        autoevalProps.evalScript,
                        autoevalProps.assets,
                        autoevalProps.maxTime,
                        autoevalProps.maxMem,
                    )
                else null
            )
        ).await()

        successMessage { "Ülesanne salvestatud" }
        recreate()
        return true
    }

    private suspend fun wishesToCancel() =
        if (hasUnsavedChanges())
            window.confirm("Siin lehel on salvestamata muudatusi. Kas oled kindel, et soovid muutmise lõpetada ilma salvestamata?")
        else true
}
