package pages.embed_anon_autoassess

import AppProperties
import CONTENT_CONTAINER_ID
import Icons
import PageName
import components.code_editor.CodeEditorComp
import components.form.ButtonComp
import debug
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import libheaders.ResizeObserver
import pages.EasyPage
import pages.exercise.ExercisePage
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.*
import template
import kotlin.math.roundToInt

object EmbedAnonAutoassessPage : EasyPage() {
    override val pageName = PageName.EMBED_ANON_AUTOASSESS

    override val pathSchema = "/embed/exercises/{exerciseId}/summary"

    override val pageAuth = PageAuth.NONE
    override val isEmbedded = true

    private val exerciseId: String
        get() = parsePathParams()["exerciseId"]

    private var rootComp: EmbedAnonAutoassessRootComp? = null

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)

        getHtml().addClass("embedded", "light")

        if (getCurrentQueryParamValue("border") != null) {
            getHtml().addClass("bordered")
        }

        val showTitle = getCurrentQueryParamValue("title") != null
        val showTemplate = getCurrentQueryParamValue("template") != null
        val sendIframeHeightId = getCurrentQueryParamValue("dynamic-height-id")

        doInPromise {
            rootComp = EmbedAnonAutoassessRootComp(
                exerciseId,
                showTitle,
                showTemplate,
                sendIframeHeightId,
                CONTENT_CONTAINER_ID
            ).also {
                it.createAndBuild().await()
            }
        }
    }

    override fun destruct() {
        super.destruct()
        rootComp?.destroy()
    }
}

class EmbedAnonAutoassessRootComp(
    private val exerciseId: String,
    private val showTitle: Boolean,
    private val showTemplate: Boolean,
    private val frameId: String?,
    dstId: String
) : Component(null, dstId) {

    private val feedbackDstId = IdGenerator.nextId()

    private lateinit var editor: CodeEditorComp
    private lateinit var submitBtn: ButtonComp
    private lateinit var feedback: EmbedAnonAutoassessFeedbackComp

    override val children: List<Component>
        get() = listOf(editor, submitBtn, feedback)

    override fun create() = doInPromise {
        // TODO: get exercise details
        sleep(1000).await()

        val file = CodeEditorComp.File("solution.py", if (showTemplate) "print()" else null)
        editor = CodeEditorComp(
            file, placeholder = "Kirjuta lahendus siia...",
            showLineNumbers = false, showTabs = false, parent = this
        )
        submitBtn = ButtonComp(ButtonComp.Type.PRIMARY_ROUND, null, Icons.robot, { assess() }, parent = this)
        feedback = EmbedAnonAutoassessFeedbackComp(parent = this, dstId = feedbackDstId)
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
                        <ez-dst id="{{submitBtnId}}"></ez-dst>
                    </div>
                    <ez-dst id="{{editorId}}"></ez-dst>
                </div>
                <ez-dst id="{{feedbackId}}"></ez-dst>
                <div style="display: flex; align-items: center; justify-content: end; font-size: 1rem;">
                    <!-- TODO: svg -->
                    <img src="/static/favicon/mstile-70x70.png" style="width: 1.7rem; height: 1.7rem; margin-right: 3px;">
                    {{lahendus}} |<a href="{{href}}" target="_blank" style="margin-left: 3px; color: #bbb">#{{exerciseId}}</a>
                </div>
            </div>
        """.trimIndent(),
        "exerciseId" to exerciseId,
        "title" to if (showTitle) "1.1 Tervitus" else null,
        "text" to """
            <div class="paragraph">
                    <p>Koostada programm, mis väljastaks ekraanile teksti <code><strong>Tere, maailm!</strong></code>
                        täpselt sellisel kujul - koma ja hüüumärgiga.</p>
                </div>
                <details>
                    <summary class="title">Näide programmi tööst</summary>
                    <div class="content">
                        <div class="listingblock">
                            <div class="content">
                                <pre class="highlightjs highlight"><code class="language-python hljs"
                                                                         data-lang="python"><span class="hljs-meta">&gt;&gt;&gt; </span><span
                                        class="codehl run">%Run lahendus.py</span>
Tere, maailm!</code></pre>
                            </div>
                        </div>
                    </div>
                </details>
                <div class="paragraph">
                    <p>Automaatkontroll on nii korraldatud, et tõesti pole vähimadki kõrvalekalded tekstis lubatud. Nii
                        võib teil programm täiesti töötada Thonnys ilma igasuguste veateadeteta, aga kui väljastatakse
                        näiteks <code><strong>tere, maailm</strong></code>, siis automaatkontroll seda õigeks ei loe.
                    </p>
                </div>
        """.trimIndent(),
        "editorId" to editor.dstId,
        "submitBtnId" to submitBtn.dstId,
        "feedbackId" to feedback.dstId,
        "lahendus" to AppProperties.AppName,
        "href" to ExercisePage.link(exerciseId),
    )

    override fun postRender() {
        if (frameId != null) {

            debug { "Setup resize handler" }

            val observer = ResizeObserver { entries, _ ->
                entries.forEach {
                    it.borderBoxSize.firstOrNull()?.let {
                        val height = it.blockSize.roundToInt()
                        debug { "Content resized to $height px" }
                        window.parent.postMessage("$frameId|$height", "*")
                    }
                }
            }

            observer.observe(getBody())
        }
    }

    override fun renderLoading() = "Laen ülesannet..."

    private suspend fun assess() {
        val solution = editor.getActiveTabContent().orEmpty()

        feedback = EmbedAnonAutoassessFeedbackComp(
            grade = "-", feedback = "Kontrollin...", show = true, parent = this, dstId = feedbackDstId
        )
        feedback.rebuild()

        // TODO
        sleep(3000).await()

        feedback = EmbedAnonAutoassessFeedbackComp(
            grade = "42", feedback = solution, show = true, parent = this, dstId = feedbackDstId
        )
        feedback.rebuild()
    }
}

// TODO: should be able to close feedback? Should wait for new TSL HTML-style feedback
class EmbedAnonAutoassessFeedbackComp(
    private val grade: String = "",
    private val feedback: String = "",
    private val show: Boolean = false,
    parent: Component?,
    dstId: String = IdGenerator.nextId(),
) : Component(parent, dstId) {

    override fun render() = if (show) template(
        """
            <pre class="feedback auto-feedback">{{feedback}}</pre>
            <div>{{autoGradeLabel}}: <span class="grade-number">{{grade}}</span>/100</div>
        """.trimIndent(),
        "grade" to grade,
        "feedback" to feedback,
        "autoGradeLabel" to "Automaatne hinne",
    ) else ""
}