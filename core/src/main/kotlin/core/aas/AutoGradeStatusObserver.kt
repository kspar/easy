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
    private data class KeyPair(val submissionId: Long, val callerType: ObserverCallerType)

    private val statuses: MutableMap<KeyPair, Job> = ConcurrentHashMap()

    @Synchronized
    fun put(submissionId: Long, callerType: ObserverCallerType, value: Job): Job? {
        log.debug { "Submission '$submissionId' by $callerType added to auto-assess status observer." }
        return statuses.put(KeyPair(submissionId, callerType), value)
    }

    @Synchronized
    fun get(submissionId: Long, callerType: ObserverCallerType): Job? {
        log.debug { "Submission '$submissionId' by $callerType retrieved from status observer." }
        return statuses[KeyPair(submissionId, callerType)]
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
