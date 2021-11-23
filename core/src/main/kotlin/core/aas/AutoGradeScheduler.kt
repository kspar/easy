package core.aas

import core.db.Executor
import core.db.ExecutorContainerImage
import core.db.PriorityLevel
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction
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
        log.info { "Initializing ${javaClass.simpleName} by syncing executors." }
        addExecutorsFromDB()
    }


    //  fixedDelay doesn't start a next call before the last one has finished
    @Scheduled(fixedDelayString = "\${easy.core.auto-assess.fixed-delay.ms}")
    @Synchronized
    private fun grade() {
        log.debug { "Grading executors $executors" }

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
        autoExerciseId: EntityID<Long>,
        submission: String,
        priority: PriorityLevel
    ): AutoAssessment {

        val autoExercise = getAutoExerciseDetails(autoExerciseId)
        val request = mapToExecutorRequest(autoExercise, submission)
        val targetExecutor = selectExecutor(getCapableExecutors(autoExerciseId).filter { !it.drain }.toSet())

        log.debug { "Scheduling and waiting for priority '$priority' autoExerciseId '$autoExerciseId'." }

        // Synchronized as executors can be removed or added at any time.
        val executor = synchronized(this) {
            executors
                .getOrElse(targetExecutor.id) { throw ExecutorException("Executor '${targetExecutor.id}' not found.") }
                .getOrElse(priority) { throw ExecutorException("$priority not found for executor ${targetExecutor.id}.") }
        }
        return executor.scheduleAndAwait(targetExecutor, request)
    }

    /**
     *  Remove executor.
     */
    @Synchronized // Synchronized as executors can be read or added at any time.
    fun deleteExecutor(executorId: Long, force: Boolean) {
        return transaction {

            assertExecutorExists(executorId)

            // The load of the all the (priority) queues in the executor map of this executor.
            val load = executors[executorId]?.values?.sumOf { it.size() }

            // If not force removal and there is any load, deny removal
            if (!force && (load ?: 0) > 0) {
                throw InvalidRequestException("Executor load != 0 (is $load). Set 'force'=true for forced removal.")
            }

            ExecutorContainerImage.deleteWhere { ExecutorContainerImage.executor eq executorId }
            Executor.deleteWhere { Executor.id eq executorId }
            executors.remove(executorId)

            log.info { "Executor '$executorId' deleted" }
        }
    }

    @Synchronized
    fun addExecutorsFromDB() {
        // Synchronized as executors can be removed or read at any time.
        val countBefore = executors.size

        getAvailableExecutorIds().forEach {
            executors.putIfAbsent(it, PriorityLevel.values().associateWith { FunctionScheduler(::callExecutor) })
        }

        log.debug { "Checked for new executors. Executor count is now: $countBefore -> ${executors.size}." }
    }
}
