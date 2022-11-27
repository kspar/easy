package pages.embed_anon_autoassess

import AppProperties
import CONTENT_CONTAINER_ID
import Icons
import PageName
import components.code_editor.CodeEditorComp
import components.form.ButtonComp
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import pages.EasyPage
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.*
import tmRender

object EmbedAnonAutoassessPage : EasyPage() {
    override val pageName = PageName.EMBED_ANON_AUTOASSESS

    override val pathSchema = "/embed/exercises/{exerciseId}/summary"

    override val doesRequireAuthentication = false
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
        val sendIframeHeightDelay = getCurrentQueryParamValue("dynamic-height-delay")?.toIntOrNull()

        doInPromise {
            rootComp = EmbedAnonAutoassessRootComp(
                exerciseId,
                showTitle,
                showTemplate,
                sendIframeHeightId,
                sendIframeHeightDelay,
                CONTENT_CONTAINER_ID
            ).also {
                it.createAndBuild().await()
            }
        }
    }
}

class EmbedAnonAutoassessRootComp(
    private val exerciseId: String,
    private val showTitle: Boolean,
    private val showTemplate: Boolean,
    private val frameId: String?,
    private val framePollDelay: Int?,
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
        submitBtn = ButtonComp(ButtonComp.Type.PRIMARY_ROUND, "", Icons.robot, { assess() }, parent = this)
        feedback = EmbedAnonAutoassessFeedbackComp(parent = this, dstId = feedbackDstId)
    }

    override fun render() = tmRender(
        "t-c-anonauto",
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
    )

    override fun postRender() {
        if (frameId != null) {
            doInPromise {
                while (true) {
                    val h = getBody().offsetHeight
                    if (h != 0) {
                        window.parent.postMessage("$frameId|${h + 1}", "*")
                    }
                    sleep(framePollDelay ?: 200).await()
                }
            }
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
    override fun render() = if (show) tmRender(
        "t-c-anonauto-feedback",
        "grade" to grade,
        "feedback" to feedback,
        "autoGradeLabel" to "Automaatne hinne",
    ) else ""
}