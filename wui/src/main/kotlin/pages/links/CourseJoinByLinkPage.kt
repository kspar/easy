package pages.links

import PageName
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import rip.kspar.ezspa.getHtml

object CourseJoinByLinkPage : EasyPage() {

    private var root: CourseJoinByLinkComp? = null

    override val pageName = PageName.LINK_JOIN_COURSE

    override val pathSchema = "/link/{inviteId}"


    val inviteId: String
        get() = parsePathParams()["inviteId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        getHtml().addClass("wui3")

        root = CourseJoinByLinkComp(inviteId, false).also { it.createAndBuild() }
    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
        root?.destroy()
        root = null
    }

    fun link(inviteId: String) = constructPathLink(mapOf("inviteId" to inviteId))
}
