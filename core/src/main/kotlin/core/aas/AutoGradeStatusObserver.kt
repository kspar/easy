package core.aas

import kotlinx.coroutines.Job
import mu.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

enum class ObserverCallerType {
    TEACHER,
    STUDENT
}

@Service
class AutoAssessStatusObserver {
    private data class StatusObserverKey(val submissionId: Long, val callerType: ObserverCallerType)

    private val statuses: MutableMap<StatusObserverKey, Job> = ConcurrentHashMap()

    @Synchronized
    private fun get(key: StatusObserverKey) = statuses[key]

    @Synchronized
    private fun put(key: StatusObserverKey, value: Job) = statuses.put(key, value)

    fun put(submissionId: Long, callerType: ObserverCallerType, value: Job): Job? {
        log.debug { "Submission '$submissionId' by $callerType added to auto-assess status observer." }
        return put(StatusObserverKey(submissionId, callerType), value)
    }

    fun get(submissionId: Long, callerType: ObserverCallerType): Job? {
        log.debug { "Submission '$submissionId' by $callerType retrieved from status observer." }
        return get(StatusObserverKey(submissionId, callerType))
    }

    @Scheduled(fixedDelayString = "\${easy.core.auto-assess.fixed-delay-observer-clear.ms}")
    @Synchronized
    fun clearOld() {
        val totalJobs = statuses.keys.size

        if (statuses.entries.removeAll { (_, job) -> job.isCompleted }) {
            val remaining = statuses.keys.size
            val removed = totalJobs - remaining
            log.debug { "Cleared $removed finished auto-assess observer jobs. Remaining: $remaining." }
        }
    }
}
