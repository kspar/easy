package pages.exercise_in_library

import Icons
import components.BreadcrumbsComp
import components.ToastThing
import dao.ExerciseDAO
import dao.LibraryDirDAO
import debug
import kotlinx.browser.window
import kotlinx.coroutines.await
import pages.Title
import pages.exercise_in_library.editor.AutoassessEditorComp
import pages.exercise_library.DirAccess
import pages.exercise_library.createDirChainCrumbs
import pages.exercise_library.createPathChainSuffix
import pages.exercise_library.permissions_modal.PermissionsModalComp
import pages.sidenav.Sidenav
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import successMessage
import template
import translation.Str


class ExerciseRootComp(
    private val exerciseId: String,
    private val setPathSuffix: (String) -> Unit,
    dstId: String
) : Component(null, dstId) {

    private lateinit var crumbs: BreadcrumbsComp
    private lateinit var addToCourseModal: AddToCourseModalComp
    private lateinit var permissionsModal: PermissionsModalComp

    private lateinit var exercisePane: ExerciseComp
    private lateinit var tabsPane: ExerciseTabsComp

    private lateinit var initialExercise: ExerciseDAO.Exercise

    override val children: List<Component>
        get() = listOf(crumbs, exercisePane, tabsPane, addToCourseModal, permissionsModal)

    override fun create() = doInPromise {
        val exercise = try {
            ExerciseDAO.getExercise(exerciseId).await()
        } catch (e: ExerciseDAO.NoLibAccessException) {
            ToastThing(Str.noAccessToLibExerciseMsg, icon = ToastThing.ERROR_INFO, displayTime = ToastThing.LONG)
            error("No exercise lib access to exercise $exerciseId")
        }
        initialExercise = exercise

        val parents = LibraryDirDAO.getDirParents(exercise.dir_id).await().reversed()

        setPathSuffix(createPathChainSuffix(parents.map { it.name } + exercise.title))

        crumbs = BreadcrumbsComp(createDirChainCrumbs(parents, exercise.title), this)

        exercisePane = ExerciseComp(exercise, this)
        tabsPane = ExerciseTabsComp(
            exerciseId, exercise,
            ::updatePreview, ::updateTitle, ::saveExercise, ::recreate, this
        )

        addToCourseModal = AddToCourseModalComp(exerciseId, exercise.title, this)
        // Set dirId to null at first because the user might not have M access, so we don't want to try to load permissions
        permissionsModal = PermissionsModalComp(null, false, null, exercise.title, this)

        Title.update {
            it.pageTitle = exercise.title
            it.parentPageTitle = Str.exerciseLibrary
        }

        Sidenav.replacePageSection(
            Sidenav.PageSection(
                exercise.title, buildList {
                    add(Sidenav.Action(Icons.add, Str.addToCourse) {
                        val r = addToCourseModal.openWithClosePromise().await()
                        if (r != null) {
                            recreate()
                        }
                    })
                    if (exercise.effective_access == DirAccess.PRAWM) {
                        add(Sidenav.Action(Icons.addPerson, Str.share) {
                            // Set dirId here to avoid loading permissions if user has no M access
                            permissionsModal.dirId = exercise.dir_id
                            val permissionsChanged = permissionsModal.refreshAndOpen().await()
                            debug { "Permissions changed: $permissionsChanged" }
                            if (permissionsChanged)
                                successMessage { Str.permissionsChanged }
                        })
                    }
                }
            )
        )
    }

    override fun hasUnsavedChanges(): Boolean =
        exercisePane.hasUnsavedChanges() ||
                tabsPane.hasUnsavedChanges()

    override fun render(): String = template(
        """
            <div id="global-exercise">
                <ez-dst id="${crumbs.dstId}"></ez-dst>
                <ez-block-container>
                    <!-- width such that it's side-by-side on some tablets as well -->
                    <ez-block id='${exercisePane.dstId}' style='width: 46.5rem; max-width: 100rem; overflow: auto;'></ez-block>
                    <ez-block id='${tabsPane.dstId}' style='width: 46.5rem; max-width: 140rem;'></ez-block>
                </ez-block-container>
                <ez-dst id="${addToCourseModal.dstId}"></ez-dst>
                <ez-dst id="${permissionsModal.dstId}"></ez-dst>
            </div>
        """.trimIndent(),
    )

    private suspend fun recreate() {
        val selectedTab = tabsPane.getSelectedTab()
        val editorView = tabsPane.getEditorActiveView()

        createAndBuild().await()

        tabsPane.setSelectedTab(selectedTab)
        tabsPane.setEditorActiveView(editorView)
    }

    private fun updatePreview(newHtml: String) {
        exercisePane.setText(newHtml)
    }

    private fun updateTitle(newTitle: String) {
        exercisePane.setTitle(newTitle)
    }

    private suspend fun saveExercise(): Boolean {
        val exerciseProps = tabsPane.getEditedProps()
        val updatedExercise = ExerciseDAO.UpdatedExercise(
            exerciseProps.title,
            exerciseProps.textAdoc,
            exerciseProps.editedSubmission.solutionFileName,
            exerciseProps.editedSubmission.solutionFileType,
            exerciseProps.editedSubmission.editedAutoassess?.let {
                ExerciseDAO.Autoeval(
                    it.containerImage,
                    it.evalScript,
                    it.assets,
                    it.maxTime,
                    it.maxMem,
                )
            },
            exerciseProps.embedConfig,
        )

        val merge = mergeChanges(updatedExercise)

        if (merge.conflict) {
            val forceSave = window.confirm(Str.mergeConflictMsg)
            if (!forceSave)
                return false
        }

        ExerciseDAO.updateExercise(exerciseId, merge.merged).await()

        successMessage { Str.exerciseSaved }
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
                remote.assets!!.associate { it.file_name to it.file_content }.let {
                    if (remote.container_image == AutoEvalTypes.TSL_CONTAINER)
                        it.filterKeys { it == AutoassessEditorComp.TSL_SPEC_FILENAME_JSON }
                    else it
                },
                remote.max_time_sec!!,
                remote.max_mem_mb!!
            )
        else null

        val initialAutoeval = if (initial.container_image != null)
            ExerciseDAO.Autoeval(
                initial.container_image!!,
                initial.grading_script!!,
                initial.assets!!.associate { it.file_name to it.file_content }.let {
                    if (initial.container_image == AutoEvalTypes.TSL_CONTAINER)
                        it.filterKeys { it == AutoassessEditorComp.TSL_SPEC_FILENAME_JSON }
                    else it
                },
                initial.max_time_sec!!,
                initial.max_mem_mb!!
            )
        else null

        val remoteEmbed = if (remote.is_anonymous_autoassess_enabled)
            ExerciseDAO.EmbedConfig(
                remote.anonymous_autoassess_template
            )
        else null

        val initialEmbed = if (initial.is_anonymous_autoassess_enabled)
            ExerciseDAO.EmbedConfig(
                initial.anonymous_autoassess_template
            )
        else null

        val title = mergeValue(local.title, remote.title, initial.title)
        val textAdoc = mergeValue(local.textAdoc, remote.text_adoc, initial.text_adoc)
        val solutionFileName = mergeValue(local.solutionFileName, remote.solution_file_name, initial.solution_file_name)
        val solutionFileType = mergeValue(local.solutionFileType, remote.solution_file_type, initial.solution_file_type)
        val autoeval = mergeValue(local.autoeval, remoteAutoeval, initialAutoeval)
        val embed = mergeValue(local.embedConfig, remoteEmbed, initialEmbed)

        return MergeResult(
            listOf(title, textAdoc, solutionFileName, solutionFileType, autoeval, embed).any { it.second },
            ExerciseDAO.UpdatedExercise(
                title.first, textAdoc.first,
                solutionFileName.first, solutionFileType.first,
                autoeval.first, embed.first
            )
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
}
