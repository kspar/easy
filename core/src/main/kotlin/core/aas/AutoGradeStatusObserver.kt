package core.aas

import core.db.AutoGradeStatus
import core.db.Submission
import kotlinx.coroutines.Job
import mu.KotlinLogging
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
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

    private fun get(key: StatusObserverKey) = statuses[key]

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
    fun clearOld() {

        val totalJobs = statuses.keys.size
        val removed = transaction {
            statuses.filterKeys { key ->

                when (key.callerType) {
                    ObserverCallerType.STUDENT -> {

                        Submission
                            .slice(Submission.autoGradeStatus)
                            .select { Submission.id eq key.submissionId }
                            .map { it[Submission.autoGradeStatus] }
                            .single() != AutoGradeStatus.IN_PROGRESS
                    }


                    ObserverCallerType.TEACHER -> {
                        TODO("TEACHER observation is not supported")
                    }
                }
            }.keys.map { statuses.remove(it) }.size
        }

        if (totalJobs != 0) {
            log.debug { "Cleared $removed/$totalJobs auto-assess observer jobs that are NOT IN_PROGRESS." }
        }
    }
}
