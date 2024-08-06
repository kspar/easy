package pages.participants

import AppProperties
import Icons
import pages.links.MoodleCourseJoinByLinkPage
import rip.kspar.ezspa.Component
import template
import translation.Str

class ShowJoinLinkComp(
    private val email: String,
    private val moodleId: String,
    private val linkId: String,
    parent: Component,
) : Component(parent) {

    override fun render() = template(
        """ 
            <p>{{help}}</p>
            <p>{{emailLabel}}: <ez-string class='semibold'>{{email}}</ez-string></p>
            <p>{{moodleIdLabel}}: <ez-string class='semibold'>{{moodleId}}</ez-string></p>
            <ez-link-wrap style='margin-top: 4rem;'>
                ${Icons.lahendus}<ez-link>{{link}}</ez-link>                    
            </ez-link-wrap>
        """.trimIndent(),
        "email" to email,
        "moodleId" to moodleId,
        "emailLabel" to Str.email,
        "moodleIdLabel" to Str.moodleId,
        "help" to Str.courseJoinHelpText,
        "link" to AppProperties.WUI_ROOT_PRETTY + MoodleCourseJoinByLinkPage.link(linkId),
    )
}