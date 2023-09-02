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
                <p>Lahenduse keskkonda haldab ja arendab Tartu Ülikooli arvutiteaduse instituut. 
                Lahendus põhineb vabavaralisel rakendusel <a href='${AppProperties.REPO_URL}' target='_blank'>easy</a>, 
                mida arendatakse samuti arvutiteaduse instituudis.</p>
                <p>Kui sul on Lahenduse kasutamise või arenduse kohta küsimusi, või kui leidsid kuskilt vea, 
                siis tule räägi sellest <a href='{{discordUrl}}${AppProperties.DISCORD_INVITE_ID}' target='_blank'>Lahenduse Discordi serveris</a>.</p> 
                
                <p style='margin-bottom: 3rem;'>Lahenduse ja easy arendust ning ülesannete loomist on toetanud:</p>
                <img style='width: 20rem;' src='static/img/logo/ati.svg' alt='Tartu Ülikool arvutiteaduse instituut'>
                
            </ez-about>
        """.trimIndent(),
        "discordUrl" to "https://discord.gg/",
    )
}