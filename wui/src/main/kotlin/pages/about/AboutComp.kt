package pages.about

import AppProperties
import CONTENT_CONTAINER_ID
import rip.kspar.ezspa.Component
import template


class AboutComp : Component(null, CONTENT_CONTAINER_ID) {

    override fun render() = template(
        """
            <ez-about>
                <h2>Lahendusest</h2>
                <p>Lahenduse keskkonda haldab ja arendab <a href='https://cs.ut.ee/et' target='_blank'>Tartu Ülikooli arvutiteaduse instituut</a>. 
                Lahendus põhineb vabavaralisel rakendusel <a href='${AppProperties.REPO_URL}' target='_blank'>easy</a>, 
                mida arendatakse samuti arvutiteaduse instituudis.</p>
                <p>Kui sul on Lahenduse kasutamise või arenduse kohta küsimusi, või kui leidsid kuskilt vea, 
                siis tule räägi sellest <a href='{{discordUrl}}${AppProperties.DISCORD_INVITE_ID}' target='_blank'>Lahenduse Discordi serveris</a>.</p> 
                
                <p style='margin-bottom: 3rem;'>Lahenduse ja easy arendust ning ülesannete loomist on toetanud:</p>
                
                <ez-about-sponsors>
                    <img style='width: 25rem; padding: 1rem;' src='static/img/logo/harno.svg' alt=''>
                    <img style='width: 25rem; padding: 1rem;' src='static/img/logo/mkm.png' alt=''>
                    <img style='width: 20rem; padding: 1rem;' src='static/img/logo/ita.png' alt=''>
                </ez-about-sponsors>
                
            </ez-about>
        """.trimIndent(),
        "discordUrl" to "https://discord.gg/",
    )
}