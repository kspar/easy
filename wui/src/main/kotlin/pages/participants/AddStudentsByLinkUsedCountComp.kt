package pages.participants

import dao.CoursesTeacherDAO
import debug
import kotlinx.coroutines.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class AddStudentsByLinkUsedCountComp(
    private val courseId: String,
    private var usedCount: Int,
    parent: Component,
) : Component(parent) {

    private lateinit var job: Job

    override fun create() = doInPromise {
        startLiveUpdate()
    }

    override fun render() = usedCount.toString()

    override fun destroyThis() {
        super.destroyThis()
        debug { "Cancelling live update for used count" }
        job.cancel()
    }

    private fun startLiveUpdate() {
        debug { "Starting live update for used count" }
        job = MainScope().launch {
            while (true) {
                delay(10_000)
                val link = CoursesTeacherDAO.getJoinLink(courseId).await()
                val new = link?.used_count
                if (new != null && new != usedCount) {
                    debug { "Used count updated $usedCount -> $new" }
                    usedCount = new
                    createAndBuild().await()
                }
            }
        }
    }
}