package pages.links

import Auth
import PageName
import debug
import kotlinx.browser.window
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import rip.kspar.ezspa.EzSpa
import rip.kspar.ezspa.getHtml
import rip.kspar.ezspa.objOf
import translation.activeLanguage

object CourseJoinByLinkPage : EasyPage() {

    private var root: CourseJoinByLinkComp? = null

    override val pageName = PageName.LINK_JOIN_COURSE

    override val pathSchema = "/link/{inviteId}"

    // FIXME: for migration, remove
    override val pageAuth = PageAuth.OPTIONAL

    val inviteId: String
        get() = parsePathParams()["inviteId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        getHtml().addClass("wui3")

        // FIXME: for migration, remove
        when {
            inviteId == "register" -> {
                // Old register link
                debug { "Invite id == register, redirecting" }

                val url = RegisterLinkPage.link() + window.location.search
                EzSpa.PageManager.navigateTo(url)
            }
            // FIXME: for migration, replace with PageAuth.REQUIRED
            !Auth.authenticated -> {
                Auth.login(objOf("locale" to activeLanguage.localeId))
            }

            else -> {
                root = CourseJoinByLinkComp(inviteId).also { it.createAndBuild() }
            }
        }

    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
        root?.destroy()
        root = null
    }

    fun link(inviteId: String) = constructPathLink(mapOf("inviteId" to inviteId))
}
