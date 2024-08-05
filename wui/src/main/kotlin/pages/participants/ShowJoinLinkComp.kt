package pages.participants

import AppProperties
import Icons
import pages.links.MoodleCourseJoinByLinkPage
import rip.kspar.ezspa.Component
import template

class ShowJoinLinkComp(
    private val linkId: String,
    parent: Component,
) : Component(parent) {

    override fun render() = template(
        """
            <ez-link-wrap>
                ${Icons.lahendus}<ez-link>{{link}}</ez-link>                    
            </ez-link-wrap>
        """.trimIndent(),
        "link" to AppProperties.WUI_ROOT_PRETTY + MoodleCourseJoinByLinkPage.link(linkId),
    )
}