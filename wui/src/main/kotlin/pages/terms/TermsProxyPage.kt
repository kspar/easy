package pages.terms

import AppProperties
import PageName
import kotlinx.browser.window
import pages.EasyPage

// IdP is pointed here for ToS, so we have one source of truth
object TermsProxyPage : EasyPage() {
    override val pageName = PageName.TERMS_PROXY

    override val pathSchema = "/tos"

    override val pageAuth = PageAuth.NONE

    override fun build(pageStateStr: String?) {
        super.build(pageStateStr)
        window.location.href = AppProperties.TOS_URL
    }

    fun link() = constructPathLink(emptyMap())
}

