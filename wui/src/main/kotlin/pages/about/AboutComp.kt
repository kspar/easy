package pages.about

import AppProperties
import CONTENT_CONTAINER_ID
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str


class AboutComp : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var stats: StatsComp

    override val children: List<Component>
        get() = listOf(stats)


    override fun create() = doInPromise {
        stats = StatsComp(this)
    }

    override fun render() = template(
        """
            <ez-about>
                <h2>{{about}}</h2>
                <p>{{s1}} <a href='https://cs.ut.ee/et' target='_blank'>{{s2}}</a>. 
                {{s3}} <a href='${AppProperties.REPO_URL}' target='_blank'>easy</a>{{s4}}.</p>
                <p>{{s5}} <a href='{{discordUrl}}${AppProperties.DISCORD_INVITE_ID}' target='_blank'>{{s6}}</a>.</p> 
                
                $stats
                
                <p style='margin-bottom: 3rem;'>{{sponsors}}:</p>
                
                <ez-about-sponsors>
                    <img style='width: 25rem; padding: 1rem;' src='static/img/logo/harno.svg' alt=''>
                    <img style='width: 25rem; padding: 1rem;' src='static/img/logo/mkm.png' alt=''>
                    <img style='width: 20rem; padding: 1rem;' src='static/img/logo/ita.png' alt=''>
                </ez-about-sponsors>
                
            </ez-about>
        """.trimIndent(),
        "about" to Str.linkAbout,
        "s1" to Str.aboutS1,
        "s2" to Str.aboutS2,
        "s3" to Str.aboutS3,
        "s4" to Str.aboutS4,
        "s5" to Str.aboutS5,
        "s6" to Str.aboutS6,
        "sponsors" to Str.aboutSponsors,
        "discordUrl" to "https://discord.gg/",
    )
}

