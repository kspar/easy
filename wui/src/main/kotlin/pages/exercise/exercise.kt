package pages.exercise

import Icons
import Str
import components.BreadcrumbsComp
import components.EditModeButtonsComp
import components.PageTabsComp
import dao.ExerciseDAO
import dao.LibraryDirDAO
import debug
import errorMessage
import kotlinx.browser.window
import kotlinx.coroutines.await
import pages.Title
import pages.exercise_library.DirAccess
import pages.exercise_library.createDirChainCrumbs
import pages.exercise_library.createPathChainSuffix
import pages.exercise_library.permissions_modal.PermissionsModalComp
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import successMessage
import template


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
    private lateinit var addToCourseModal: AddToCourseModalComp
    private lateinit var permissionsModal: PermissionsModalComp

    // null if no write access
    private var editModeBtns: EditModeButtonsComp? = null

    private lateinit var exerciseTab: ExerciseTabComp
    private lateinit var autoassessTab: AutoAssessmentTabComp

    private lateinit var initialExercise: ExerciseDAO.Exercise

    override val children: List<Component>
        get() = listOf(crumbs, tabs, addToCourseModal, permissionsModal)

    override fun create() = doInPromise {
        val exercise = try {
            ExerciseDAO.getExercise(exerciseId).await()
        } catch (e: ExerciseDAO.NoLibAccessException) {
            errorMessage { "Sul pole ülesandekogus sellele ülesandele ligipääsu" }
            error("No exercise lib access to exercise $exerciseId")
        }
        initialExercise = exercise

        val parents = LibraryDirDAO.getDirParents(exercise.dir_id).await().reversed()

        setPathSuffix(createPathChainSuffix(parents.map { it.name } + exercise.title))

        crumbs = BreadcrumbsComp(createDirChainCrumbs(parents, exercise.title), this)

        tabs = PageTabsComp(
            tabs = buildList {
                add(
                    PageTabsComp.Tab("Ülesanne", preselected = true, id = exerciseTabId) {
                        ExerciseTabComp(exercise, ::validChanged, it)
                            .also { exerciseTab = it }
                    }
                )

                add(
                    PageTabsComp.Tab(
                        "Automaatkontroll",
                        id = autoassessTabId,
                        onActivate = { autoassessTab.refreshTSLTabs() }) {
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
            trailerComponent = if (exercise.effective_access >= DirAccess.PRAW) {
                {
                    EditModeButtonsComp(
                        { editModeChanged(it) },
                        { saveExercise() },
                        postModeChange = { tabs.refreshIndicator() },
                        canCancel = { wishesToCancel() },
                        parent = it
                    ).also { editModeBtns = it }
                }
            } else null,
            parent = this
        )
        addToCourseModal = AddToCourseModalComp(exerciseId, exercise.title, this)
        // Set dirId to null at first because the user might not have M access, so we don't want to try to load permissions
        permissionsModal = PermissionsModalComp(null, false, null, exercise.title, this)

        Title.update {
            it.pageTitle = exercise.title
            it.parentPageTitle = Str.exerciseLibrary()
        }

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                exercise.title, buildList {
                    add(Sidenav.Action(Icons.add, "Lisa kursusele") {
                        val r = addToCourseModal.openWithClosePromise().await()
                        if (r != null) {
                            recreate()
                        }
                    })
                    if (exercise.effective_access == DirAccess.PRAWM) {
                        add(Sidenav.Action(Icons.addPerson, "Jagamine") {
                            // Set dirId here to avoid loading permissions if user has no M access
                            permissionsModal.dirId = exercise.dir_id
                            val permissionsChanged = permissionsModal.refreshAndOpen().await()
                            debug { "Permissions changed: $permissionsChanged" }
                            if (permissionsChanged)
                                successMessage { "Õigused muudetud" }
                        })
                    }
                }
            )
        )
    }

    override fun hasUnsavedChanges(): Boolean =
        exerciseTab.hasUnsavedChanges() ||
                autoassessTab.hasUnsavedChanges()

    override fun render(): String = template(
        """
            <div id="global-exercise">
                <ez-dst id="{{crumbs}}"></ez-dst>
                <ez-dst id="{{tabs}}"></ez-dst>
                <ez-dst id="{{addToCourseModal}}"></ez-dst>
                <ez-dst id="{{permissionsModal}}"></ez-dst>
            </div>
        """.trimIndent(),
        "crumbs" to crumbs.dstId,
        "tabs" to tabs.dstId,
        "addToCourseModal" to addToCourseModal.dstId,
        "permissionsModal" to permissionsModal.dstId,
    )

    private suspend fun recreate() {
        val selectedTab = tabs.getSelectedTab()
        val editorView = autoassessTab.getEditorActiveView()

        createAndBuild().await()

        tabs.setSelectedTab(selectedTab)
        autoassessTab.setEditorActiveView(editorView)
    }

    private fun validChanged(_notUsed: Boolean) {
        editModeBtns?.setSaveEnabled(exerciseTab.isValid() && autoassessTab.isValid())
    }

    private suspend fun editModeChanged(nowEditing: Boolean): Boolean {
        // Check if exercise has changed before editing
        if (nowEditing && hasExerciseChanged()) {
            successMessage { "Seda ülesannet on vahepeal muudetud, näitan uut versiooni" }
            recreate()
            return false
        }

        // exercise tab: title, text editor
        exerciseTab.setEditable(nowEditing)

        // aa tab: attrs, editor
        val editorView = autoassessTab.getEditorActiveView()
        autoassessTab.setEditable(nowEditing)
        autoassessTab.setEditorActiveView(editorView)
        return true
    }

    private suspend fun saveExercise(): Boolean {
        val exerciseProps = exerciseTab.getEditedProps()
        val autoevalProps = autoassessTab.getEditedProps()
        val updatedExercise = ExerciseDAO.UpdatedExercise(
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

        val merge = mergeChanges(updatedExercise)

        if (merge.conflict) {
            val forceSave = window.confirm(
                "Seda ülesannet on vahepeal muudetud ja lokaalsed muudatused lähevad varem tehtud muudatustega konflikti. " +
                        "Kas soovid vahepeal tehtud muudatused oma lokaalsete muudatustega üle kirjutada?"
            )
            if (!forceSave)
                return false
        }

        ExerciseDAO.updateExercise(exerciseId, merge.merged).await()

        successMessage { "Ülesanne salvestatud" }
        recreate()
        return true
    }

    data class MergeResult(val conflict: Boolean, val merged: ExerciseDAO.UpdatedExercise)

    private suspend fun mergeChanges(local: ExerciseDAO.UpdatedExercise): MergeResult {
        val remote = ExerciseDAO.getExercise(exerciseId).await()
        val initial = initialExercise

        val remoteAutoeval = if (remote.container_image != null)
            ExerciseDAO.Autoeval(
                remote.container_image!!,
                remote.grading_script!!,
                remote.assets!!.associate { it.file_name to it.file_content },
                remote.max_time_sec!!,
                remote.max_mem_mb!!
            )
        else null

        val initialAutoeval = if (initial.container_image != null)
            ExerciseDAO.Autoeval(
                initial.container_image!!,
                initial.grading_script!!,
                initial.assets!!.associate { it.file_name to it.file_content },
                initial.max_time_sec!!,
                initial.max_mem_mb!!
            )
        else null

        val title = mergeValue(local.title, remote.title, initial.title)
        val textAdoc = mergeValue(local.textAdoc, remote.text_adoc, initial.text_adoc)
        val textHtml = mergeValue(local.textHtml, remote.text_html, initial.text_html)
        val autoeval = mergeValue(local.autoeval, remoteAutoeval, initialAutoeval)

        return MergeResult(
            listOf(title, textAdoc, textHtml, autoeval).any { it.second },
            ExerciseDAO.UpdatedExercise(title.first, textAdoc.first, textHtml.first, autoeval.first)
        )
    }

    private fun <T> mergeValue(local: T, remote: T, initial: T): Pair<T, Boolean> = when {
        // local unchanged
        local == initial -> remote to false
        // remote unchanged
        remote == initial -> local to false
        // both changed to same value - no conflict
        local == remote -> local to false
        // both changed to different values - conflict
        else -> local to true
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
            window.confirm("Siin lehel on salvestamata muudatusi. Kas oled kindel, et soovid muutmise lõpetada ilma salvestamata?")
        else true
}
