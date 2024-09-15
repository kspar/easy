package pages.exercise_in_library

import AppProperties
import components.code_editor.CodeEditorComp
import components.form.ToggleComp
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.embed_anon_autoassess.EmbedAnonAutoassessPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.dstIfNotNull
import template
import translation.Str

class EmbedModalOptionsComp(
    private val exerciseId: String,
    private val courseId: String? = null,
    private val courseExId: String? = null,
) : Component() {

    private lateinit var toggle: ToggleComp
    private var editor: CodeEditorComp? = null

    override val children: List<Component>
        get() = listOfNotNull(toggle, editor)

    override fun create() = doInPromise {
        val exercise = ExerciseDAO.getExercise(exerciseId).await()

        toggle = ToggleComp(
            Str.disabled, Str.enabled,
            initialValue = exercise.is_anonymous_autoassess_enabled,
            onValueChange = {
                updateEmbedSettings(it)
                createAndBuild().await()
            },
            parent = this,
        )

        val src = AppProperties.WUI_ROOT + EmbedAnonAutoassessPage.link(
            exerciseId,
            showTitle = true, showBorder = true, showSubmit = false, showTemplate = true, dynamicResize = true,
            titleAlias = null, titleForPath = exercise.title,
            linkCourseId = courseId, linkCourseExerciseId = courseExId,
        )

        editor = if (exercise.is_anonymous_autoassess_enabled)
            CodeEditorComp(
                listOf(
                    CodeEditorComp.File("HTML", createHtmlSnippet(src), isEditable = false),
                    CodeEditorComp.File("PmWiki", createPmWikiSnippet(src), isEditable = false),
                ),
                lineNumbers = false,
                softWrap = true,
                parent = this
            )
        else null
    }

    override fun render() = template(
        """
            <ez-flex style='margin-bottom: 3rem;'>
                $toggle
            </ez-flex>
            ${editor.dstIfNotNull()}
        """.trimIndent()
    )

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
}