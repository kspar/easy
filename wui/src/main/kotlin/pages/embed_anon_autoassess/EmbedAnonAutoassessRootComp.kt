package pages.embed_anon_autoassess

import AppProperties
import Icons
import JsonUtil
import components.code_editor.CodeEditorComp
import components.form.ButtonComp
import dao.AnonymousExerciseDAO
import dao.CourseExercisesStudentDAO
import dao.ExerciseDAO
import debug
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import libheaders.ResizeObserver
import pages.course_exercise.ExerciseFeedbackComp
import pages.exercise_in_library.ExercisePage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getBody
import template
import kotlin.math.roundToInt

class EmbedAnonAutoassessRootComp(
    private val exerciseId: String,
    private val showTitle: Boolean,
    private val showTemplate: Boolean,
    private val dynamicResize: Boolean,
    dstId: String
) : Component(null, dstId) {

    private lateinit var exercise: AnonymousExerciseDAO.AnonExercise

    private lateinit var editor: CodeEditorComp
    private lateinit var submitBtn: ButtonComp
    private lateinit var feedback: ExerciseFeedbackComp

    override val children: List<Component>
        get() = listOf(editor, submitBtn, feedback)

    override fun create() = doInPromise {
        exercise = AnonymousExerciseDAO.getExerciseDetails(exerciseId).await()

        val file =
            CodeEditorComp.File("lahendus.py", if (showTemplate) exercise.anonymous_autoassess_template else null)
        editor = CodeEditorComp(
            file,
            placeholder = "Kirjuta lahendus siia...",
            showLineNumbers = false, showTabs = false, parent = this
        )
        submitBtn = ButtonComp(ButtonComp.Type.PRIMARY_ROUND, null, Icons.robot, { assess() }, parent = this)
        feedback = ExerciseFeedbackComp(null, null, null, parent = this)
    }

    override fun render() = template(
        """
            <div id="anonauto">
                {{#title}}<h2>{{title}}</h2>{{/title}}
                <div id="exercise-text">
                    {{{text}}}
                </div>
                <div style="position: relative">
                    <div style="position: absolute; right: 10px; top: 10px;">
                        $submitBtn
                    </div>
                    $editor
                </div>
                $feedback
                <div style="display: flex; align-items: center; justify-content: end; font-size: 1rem;">
                    <!-- TODO: svg -->
                    <img src="/static/favicon/mstile-70x70.png" style="width: 1.7rem; height: 1.7rem; margin-right: 3px;">
                    {{lahendus}} | <a href="{{href}}" target="_blank" style="margin-left: 3px; color: #bbb">#{{exerciseId}}</a>
                </div>
            </div>
        """.trimIndent(),
        "exerciseId" to exerciseId,
        "title" to if (showTitle) exercise.title else null,
        "text" to exercise.text_html,
        "lahendus" to AppProperties.AppName,
        "href" to ExercisePage.link(exerciseId),
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
                        val msgStr = JsonUtil.encodeToString(FrameResizeMessage.serializer(), msg)
                        window.parent.postMessage(msgStr, "*")
                    }
                }
            }

            observer.observe(getBody())
        }
    }

    override fun renderLoading() = "Laen ülesannet..."

    private suspend fun assess() {
        val solution = editor.getActiveTabContent().orEmpty()

        feedback.clearAll()

        val f = AnonymousExerciseDAO.submit(exerciseId, solution).await()

        feedback.validGrade = CourseExercisesStudentDAO.ValidGrade(f.grade, ExerciseDAO.GraderType.AUTO)
        feedback.autoFeedback = f.feedback
        feedback.rebuild()
    }
}