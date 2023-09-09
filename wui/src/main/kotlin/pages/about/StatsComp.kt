package pages.about

import dao.StatisticsDAO
import debug
import kotlinx.coroutines.*
import libheaders.TypeError
import rip.kspar.ezspa.Component
import template
import translation.Str

class StatsComp(parent: Component) : Component(parent) {

    private var current: StatisticsDAO.Stats? = null
    private var job: Job? = null

    override fun render() = current?.let {
        template(
            """
                <div>{{autogradeLabel}}: <ez-about-live-stat>{{autograding}}</ez-about-live-stat></div>
                <div>{{subsLabel}}: <ez-about-live-stat>{{totalSubs}}</ez-about-live-stat></div>
                <div>{{accsLabel}}: <ez-about-live-stat>{{totalAccs}}</ez-about-live-stat></div>
            """.trimIndent(),
            "autograding" to it.in_auto_assessing,
            "totalSubs" to it.total_submissions,
            "totalAccs" to it.total_users,
            "autogradeLabel" to Str.statsAutograding,
            "subsLabel" to Str.statsSubmissions,
            "accsLabel" to Str.statsAccounts,
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