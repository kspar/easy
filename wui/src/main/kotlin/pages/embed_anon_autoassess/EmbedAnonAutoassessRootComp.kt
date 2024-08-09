package pages.embed_anon_autoassess

import AppProperties
import Icons
import components.code_editor.CodeEditorComp
import components.form.OldButtonComp
import components.text.WarningComp
import dao.AnonymousExerciseDAO
import debug
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import libheaders.ResizeObserver
import pages.course_exercise.ExerciseAutoFeedbackHolderComp
import pages.course_exercise.ExerciseSummaryPage
import pages.exercise_in_library.ExercisePage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getBody
import stringify
import template
import translation.Str
import kotlin.math.roundToInt

class EmbedAnonAutoassessRootComp(
    private val exerciseId: String,
    private val showTitle: Boolean,
    private val titleAlias: String?,
    private val showTemplate: Boolean,
    private val dynamicResize: Boolean,
    private val submit: Boolean,
    private val courseExerciseLink: CourseExercise?,
    dstId: String
) : Component(null, dstId) {

    data class CourseExercise(val courseId: String, val courseExerciseId: String)

    private lateinit var exercise: AnonymousExerciseDAO.AnonExercise

    private var editor: CodeEditorComp? = null
    private var submitBtn: OldButtonComp? = null

    private lateinit var feedback: ExerciseAutoFeedbackHolderComp
    private lateinit var warning: WarningComp

    private var showSubmit: Boolean = false

    override val children: List<Component>
        get() = listOfNotNull(editor, submitBtn, feedback, warning)

    override fun create() = doInPromise {
        exercise = AnonymousExerciseDAO.getExerciseDetails(exerciseId).await()

        showSubmit = submit && exercise.submit_allowed

        if (showSubmit) {
            editor = CodeEditorComp(
                CodeEditorComp.File("lahendus.py", if (showTemplate) exercise.anonymous_autoassess_template else null),
                showLineNumbers = false, showTabs = false, parent = this
            )
            submitBtn = OldButtonComp(OldButtonComp.Type.PRIMARY_ROUND, null, Icons.robot, { assess() }, parent = this)
        }

        warning = if (submit && !exercise.submit_allowed)
            WarningComp(Str.noAutogradeWarning, parent = this)
        else
            WarningComp(parent = this)

        feedback = ExerciseAutoFeedbackHolderComp(null, false, false, parent = this)
    }

    override fun render() = template(
        """
            <div id="anonauto">
                {{#title}}<h2>{{title}}</h2>{{/title}}
                <div class="exercise-text">
                    {{{text}}}
                </div>
                $warning
                
                {{#hasSubmit}}
                    <div style="position: relative">
                        <div style="position: absolute; right: 10px; top: 10px;">
                            $submitBtn
                        </div>
                        $editor
                    </div>
                {{/hasSubmit}}
                $feedback
                {{#hasLink}}
                    <ez-link style='display: flex; margin: 2rem 0;'>
                        <a href='{{linkHref}}' target='_blank'>{{title}} · {{lahendus}}</a>
                    </ez-link>
                {{/hasLink}}
                <div style="display: flex; align-items: center; justify-content: end; font-size: 1rem; margin-top: 1rem;">
                    <!-- TODO: svg -->
                    <img src="/static/favicon/mstile-70x70.png" style="width: 1.7rem; height: 1.7rem; margin-right: .3rem;">
                    {{lahendus}} | <a href="{{href}}" target="_blank" style="margin-left: 3px; color: #bbb">#{{exerciseId}}</a>
                </div>
            </div>
        """.trimIndent(),
        "exerciseId" to exerciseId,
        "title" to if (showTitle) titleAlias ?: exercise.title else null,
        "text" to exercise.text_html,
        "lahendus" to AppProperties.AppName,
        "href" to ExercisePage.link(exerciseId),
        "hasSubmit" to showSubmit,
        "hasLink" to (courseExerciseLink != null),
        "linkHref" to courseExerciseLink?.let { ExerciseSummaryPage.link(it.courseId, it.courseExerciseId) },
    )

    @Serializable
    data class FrameResizeMessage(
        val url: String,
        val height: Int,
        val type: String = "ez-frame-resize",
    )

    override fun postRender() {
        if (dynamicResize) {

            debug { "Setup resize handler" }

            val observer = ResizeObserver { entries, _ ->
                entries.forEach {
                    it.borderBoxSize.firstOrNull()?.let {
                        val height = it.blockSize.roundToInt()
                        debug { "Content resized to $height px" }
                        val msg = FrameResizeMessage(window.location.toString(), height)
                        val msgStr = FrameResizeMessage.serializer().stringify(msg)
                        window.parent.postMessage(msgStr, "*")
                    }
                }
            }

            observer.observe(getBody())
        }
    }

    override fun renderLoading() = "Laen ülesannet..."

    private suspend fun assess() {
        val solution = editor?.getActiveTabContent().orEmpty()

        feedback.clear()

        val result = AnonymousExerciseDAO.submit(exerciseId, solution).await()

        feedback.setFeedback(result.feedback, false)
    }
}