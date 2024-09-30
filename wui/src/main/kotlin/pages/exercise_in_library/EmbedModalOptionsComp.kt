package pages.exercise_in_library

import AppProperties
import components.code_editor.CodeEditorComp
import components.form.CheckboxComp
import components.form.ToggleComp
import dao.ExerciseDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.embed_anon_autoassess.EmbedAnonAutoassessPage
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import storage.Key
import storage.LocalStore
import stringify
import template
import translation.Str

class EmbedModalOptionsComp(
    private val exerciseId: String,
    private val canEdit: Boolean,
    private val courseId: String? = null,
    private val courseExId: String? = null,
    private val titleAlias: String? = null,
) : Component() {

    @Serializable
    data class View(
        val allowTestingCheckboxState: CheckboxComp.Value = CheckboxComp.Value.UNCHECKED,
        val selectedTabName: String = "HTML"
    )

    private lateinit var toggle: ToggleComp
    private var allowTestingCheckbox: CheckboxComp? = null
    private var editor: CodeEditorComp? = null

    private var embedAllowed = false
    private var exerciseTitle = ""

    override val children: List<Component>
        get() = listOfNotNull(toggle, allowTestingCheckbox, editor)

    override fun create() = doInPromise {
        val exercise = ExerciseDAO.getExercise(exerciseId).await()
        embedAllowed = exercise.is_anonymous_autoassess_enabled
        exerciseTitle = exercise.title

        toggle = ToggleComp(
            Str.disabled, Str.enabled,
            initialValue = embedAllowed,
            isDisabled = !canEdit,
            onValueChange = {
                updateEmbedSettings(it)
                createAndBuild().await()
            },
            parent = this,
        )

        val view = LocalStore.get(Key.EXERCISE_EMBED_OPTIONS_VIEW)?.parseToOrCatch(View.serializer()) ?: View()

        allowTestingCheckbox = if (embedAllowed && exercise.isAutoAssessable)
            CheckboxComp(
                Str.embedAllowTesting,
                value = view.allowTestingCheckboxState,
                onChange = {
                    updateEditorContent()
                    saveView(view)
                },
                parent = this
            )
        else null

        editor = if (embedAllowed)
            CodeEditorComp(
                listOf(
                    CodeEditorComp.File(
                        "HTML", "",
                        isEditable = false, startActive = view.selectedTabName == "HTML"
                    ),
                    CodeEditorComp.File(
                        "PmWiki", "",
                        isEditable = false, startActive = view.selectedTabName == "PmWiki"
                    ),
                ),
                lineNumbers = false,
                softWrap = true,
                onFileSwitch = {
                    saveView(view)
                },
                parent = this
            )
        else null
    }

    override fun render() = template(
        """
            <div style='margin-bottom: 3rem;'>
                $toggle
                <ez-flex style='margin-top: 1rem;'>
                    ${allowTestingCheckbox.dstIfNotNull()}
                </ez-flex>
            </div>
            ${editor.dstIfNotNull()}
        """.trimIndent()
    )

    override fun postChildrenBuilt() {
        doInPromise {
            updateEditorContent()
        }
    }

    private suspend fun updateEditorContent() {
        if (!embedAllowed)
            return

        val src = AppProperties.WUI_ROOT + EmbedAnonAutoassessPage.link(
            exerciseId = exerciseId,
            showTitle = true, showBorder = true, showSubmit = allowTestingCheckbox?.isChecked ?: false,
            showTemplate = true, dynamicResize = true,
            titleAlias = titleAlias, titleForPath = exerciseTitle,
            linkCourseId = courseId, linkCourseExerciseId = courseExId,
        )

        editor!!.setContent(filename = "HTML", content = createHtmlSnippet(src))
        editor!!.setContent(filename = "PmWiki", content = createPmWikiSnippet(src))
    }

    private suspend fun updateEmbedSettings(embedNowEnabled: Boolean) {
        ExerciseDAO.setExerciseEmbed(exerciseId, embedNowEnabled).await()
    }

    private fun createHtmlSnippet(src: String) =
        """
            <script src="${AppProperties.EMBED_RESIZER_SCRIPT_URL}"></script>
            <iframe src="$src" width="100%" style="border: none;"></iframe>
        """.trimIndent()

    private fun createPmWikiSnippet(src: String) =
        """
            |(:html:)
            |${createHtmlSnippet(src)}
            |(:htmlend:)
        """.trimMargin()

    private fun saveView(initialView: View) {
        LocalStore.set(
            Key.EXERCISE_EMBED_OPTIONS_VIEW,
            View.serializer().stringify(
                View(
                    allowTestingCheckboxState = allowTestingCheckbox?.value ?: initialView.allowTestingCheckboxState,
                    selectedTabName = editor?.getActiveFilename() ?: initialView.selectedTabName,
                )
            )
        )
    }
}