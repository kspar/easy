package pages.embed_anon_autoassess

import CONTENT_CONTAINER_ID
import PageName
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import pages.EasyPage
import queries.getCurrentQueryParamValue
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml

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
        val dynamicResize = getCurrentQueryParamValue("resize-handler") != null

        doInPromise {
            rootComp = EmbedAnonAutoassessRootComp(
                exerciseId,
                showTitle,
                showTemplate,
                dynamicResize,
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
