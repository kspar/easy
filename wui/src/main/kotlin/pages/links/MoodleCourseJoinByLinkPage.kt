package pages.links

import PageName
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import pages.EasyPage
import rip.kspar.ezspa.getHtml

object MoodleCourseJoinByLinkPage : EasyPage() {

    private var root: CourseJoinByLinkComp? = null

    override val pageName = PageName.LINK_JOIN_MOODLE_COURSE

    override val pathSchema = "/moodle/link/{inviteId}"


    val inviteId: String
        get() = parsePathParams()["inviteId"]

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        getHtml().addClass("wui3")

        root = CourseJoinByLinkComp(inviteId, true).also { it.createAndBuild() }
    }

    override fun destruct() {
        super.destruct()
        getHtml().removeClass("wui3")
        root?.destroy()
        root = null
    }

    fun link(inviteId: String) = constructPathLink(mapOf("inviteId" to inviteId))
}
