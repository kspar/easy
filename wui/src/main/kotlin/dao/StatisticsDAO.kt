package dao

import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.doInPromise
import kotlin.js.Promise

object StatisticsDAO {

    @Serializable
    data class Stats(
        val in_auto_assessing: Int,
        val total_submissions: Int,
        val total_users: Int,
    )

    fun waitForCommonStats(existingStats: Stats?): Promise<Stats> = doInPromise {
        debug { "Waiting for new stats" }

        val body = if (existingStats != null) mapOf(
            "in_auto_assessing" to existingStats.in_auto_assessing,
            "total_submissions" to existingStats.total_submissions,
            "total_users" to existingStats.total_users,
        ) else null

        fetchEms("/statistics/common", ReqMethod.POST, body,
            successChecker = { http200 }
        ).await().parseTo(Stats.serializer()).await()
    }
}