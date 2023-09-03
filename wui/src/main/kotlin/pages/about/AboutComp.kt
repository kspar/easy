package pages.about

import AppProperties
import CONTENT_CONTAINER_ID
import dao.StatisticsDAO
import debug
import kotlinx.coroutines.*
import libheaders.TypeError
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template


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
                <h2>Lahendusest</h2>
                <p>Lahenduse keskkonda haldab ja arendab <a href='https://cs.ut.ee/et' target='_blank'>Tartu Ülikooli arvutiteaduse instituut</a>. 
                Lahendus põhineb vabavaralisel rakendusel <a href='${AppProperties.REPO_URL}' target='_blank'>easy</a>, 
                mida arendatakse samuti arvutiteaduse instituudis.</p>
                <p>Kui sul on Lahenduse kasutamise või arenduse kohta küsimusi, või kui leidsid kuskilt vea, 
                siis tule räägi sellest <a href='{{discordUrl}}${AppProperties.DISCORD_INVITE_ID}' target='_blank'>Lahenduse Discordi serveris</a>.</p> 
                
                $stats
                
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

class StatsComp(parent: Component) : Component(parent) {

    private var current: StatisticsDAO.Stats? = null
    private var job: Job? = null

    override fun render() = current?.let {
        template(
            """
                <div>Lahendusi, mida hetkel kontrollitakse: <ez-about-live-stat>{{autograding}}</ez-about-live-stat></div>
                <div>Esitusi kokku: <ez-about-live-stat>{{totalSubs}}</ez-about-live-stat></div>
                <div>Kasutajaid kokku: <ez-about-live-stat>{{totalAccs}}</ez-about-live-stat></div>
            """.trimIndent(),
            "autograding" to it.in_auto_assessing,
            "totalSubs" to it.total_submissions,
            "totalAccs" to it.total_users,
        )
    } ?: ""

    override fun postRender() {
        startLiveUpdate()
    }


    private fun startLiveUpdate() {
        debug { "Starting live update for stats" }
        job = MainScope().launch {
            while (true) {
                delay(500)
                val stats = try {
                    StatisticsDAO.waitForCommonStats(current).await()
                } catch (e: TypeError) {
                    // Apache times out requests after some time and fetch then throws TypeError
                    debug { "TypeError, continue" }
                    continue
                }
                debug { "Updated stats $current -> $stats" }
                current = stats
                createAndBuild().await()
            }
        }
    }

    override fun destroyThis() {
        super.destroyThis()
        debug { "Cancelling live update for stats" }
        job?.cancel()
    }
}