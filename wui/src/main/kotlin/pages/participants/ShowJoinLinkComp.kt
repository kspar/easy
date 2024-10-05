package pages.participants

import AppProperties
import Icons
import components.ToastThing
import components.form.IconButtonComp
import copyToClipboard
import kotlinx.coroutines.await
import pages.links.MoodleCourseJoinByLinkPage
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str

class ShowJoinLinkComp(
    private val email: String,
    private val moodleId: String,
    private val linkId: String,
    parent: Component,
) : Component(parent) {

    private lateinit var copyBtn: IconButtonComp

    override val children
        get() = listOf(copyBtn)

    override fun create() = doInPromise {
        copyBtn = IconButtonComp(
            Icons.copy, Str.doCopy,
            onClick = {
                copyToClipboard(
                    AppProperties.WUI_ROOT + MoodleCourseJoinByLinkPage.link(linkId)
                ).await()
                ToastThing(Str.copied)
            },
            parent = this
        )
    }

    override fun render() = template(
        """ 
            <p>{{help}}</p>
            <p>{{emailLabel}}: <ez-string class='semibold'>{{email}}</ez-string></p>
            <p>{{moodleIdLabel}}: <ez-string class='semibold'>{{moodleId}}</ez-string></p>
            <ez-link-wrap style='margin-top: 4rem;'>
                <ez-link-icon>${Icons.lahendus}</ez-link-icon>
                <ez-link>{{link}}</ez-link>
                <ez-link-copy>$copyBtn</ez-link-copy>
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