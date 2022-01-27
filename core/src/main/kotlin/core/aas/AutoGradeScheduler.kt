package core.aas

import core.db.*
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.min


private val log = KotlinLogging.logger {}

@Service
class AutoGradeScheduler : ApplicationListener<ContextRefreshedEvent> {

    private val executors: MutableMap<Long, Map<PriorityLevel, FunctionScheduler<AutoAssessment>>> = ConcurrentHashMap()

    // Global index for picking the next queue
    private var queuePickerIndex = AtomicInteger()

    @Synchronized
    override fun onApplicationEvent(p0: ContextRefreshedEvent) {
        log.info { "Initializing ${javaClass.simpleName} by syncing executors and checking AutoAssess statuses." }
        addExecutorsFromDB()
        statusInProgressToFailed()
    }

    //  fixedDelay doesn't start a next call before the last one has finished
    @Scheduled(fixedDelayString = "\${easy.core.auto-assess.fixed-delay.ms}")
    @Synchronized
    private fun grade() {
        if (executors.values.flatMap { it.values }.sumOf { it.size() } != 0) {
            log.debug { "Grading executors $executors" }
        }

        executors.forEach { (executorId, schedulers) ->
            val waiting = schedulers.values.sumOf { it.countWaiting() }
            val running = schedulers.values.sumOf { it.countActive() }
            val maxLoad = getExecutorMaxLoad(executorId)

            // Number of jobs planned to be executed, ensures that loop finishes
            val executableCount = min(waiting, maxLoad - running)

            repeat(executableCount) {
                val startable = schedulers.values.filter { it.hasWaiting() }
                if (startable.isEmpty()) return

                val queuePicker = queuePickerIndex.incrementAndGet().absoluteValue
                startable[queuePicker % startable.size].startNext()
            }
        }
    }


    suspend fun submitAndAwait(
        autoExerciseId: Long,
        submission: String,
        priority: PriorityLevel
    ): AutoAssessment {

        val autoExercise = getAutoExerciseDetails(autoExerciseId)
        val request = autoExercise.mapToExecutorRequest(submission)
        val selectedExecutor = chooseOptimalExecutor(autoExerciseId, priority)

        return selectedExecutor.functionScheduler.scheduleAndAwait(selectedExecutor.capableExecutor, request)
    }

    @Synchronized
    private fun chooseOptimalExecutor(autoExerciseId: Long, priority: PriorityLevel) =
        getCapableExecutors(autoExerciseId)
            .filterNot { it.drain }
            .mapNotNull { it.associateWithSchedulerOrNull(executors[it.id]?.get(priority)) }
            .minByOrNull { it.functionScheduler.size().toDouble() / it.capableExecutor.maxLoad }
            ?: throw NoExecutorsException("No capable executors found for this auto exercise")


    @Synchronized
    fun deleteExecutor(executorId: Long, force: Boolean) {
        return transaction {

            // The load of the all the (priority) queues in the executor map of this executor.
            val load = executors[executorId]?.values?.sumOf { it.size() } ?: 0

            // If not force removal and there is any load, deny removal
            if (!force && load > 0) {
                throw InvalidRequestException("Executor load != 0 (is $load). Set 'force'=true for forced removal.")
            }

            ExecutorContainerImage.deleteWhere { ExecutorContainerImage.executor eq executorId }
            Executor.deleteWhere { Executor.id eq executorId }

            executors[executorId]?.forEach { it.value.killScheduler() }
            executors.remove(executorId)

            log.info { "Executor '$executorId' deleted" }
        }
    }

    @Synchronized
    fun addExecutorsFromDB() {
        val countBefore = executors.size

        getAvailableExecutorIds().forEach {
            executors.putIfAbsent(it, PriorityLevel.values().associateWith { FunctionScheduler(::callExecutor) })
        }

        log.debug { "Checked for new executors. Executor count is now: $countBefore -> ${executors.size}." }
    }

    /**
     * Set AutoGradeStatus in the database from IN_PROGRESS to FAILED. Keeps database consistent in case of unexpected
     * outages, where some failed jobs can have database state of IN_PROGRESS. Consistency is necessary as some suspending
     * services rely on the correct IN_PROGRESS autograde state.
     */
    private fun statusInProgressToFailed() {
        val updated = transaction {
            Submission.update({ Submission.autoGradeStatus eq AutoGradeStatus.IN_PROGRESS }) {
                it[autoGradeStatus] = AutoGradeStatus.FAILED
            }
        }
        log.debug { "Set $updated AutoGradeStatus from ${AutoGradeStatus.IN_PROGRESS} to ${AutoGradeStatus.FAILED}." }
    }
}
