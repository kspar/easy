package pages.about

import PageName
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.getHtml

object AboutPage : EasyPage() {
    override val pageName = PageName.ABOUT

    override val pathSchema = "/about"

    override val pageAuth = PageAuth.OPTIONAL

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        getHtml().addClass("wui3")

        doInPromise {
            AboutComp().createAndBuild().await()
        }
    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
    }

    fun link() = constructPathLink(emptyMap())
}

