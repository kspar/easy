package core.aas

import core.db.Executor
import core.db.ExecutorContainerImage
import core.db.PriorityLevel
import core.exception.InvalidRequestException
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.min


private val log = KotlinLogging.logger {}

@Service
class AutoGradeScheduler : ApplicationListener<ContextRefreshedEvent> {

    @Value("\${easy.core.auto-assess.allowed-wait-for-user.ms}")
    private lateinit var allowedWaitingTimeUserMs: String

    /**
     * [submitAndAwait] expects that map of [executors] is synced with database. However [addExecutorsFromDB] can be called
     * anytime, therefore [executorLock] is used to synchronize [submitAndAwait] and [addExecutorsFromDB] to avoid
     * cases where [submitAndAwait] expects to see an executor in [executors] that otherwise might be already in db, but
     * not in map.
     */
    private val executorLock = UUID.randomUUID().toString()

    private val executors: MutableMap<Long, SortedMap<PriorityLevel, FunctionScheduler<AutoAssessment>>> =
        ConcurrentHashMap()

    // Global index for picking the next queue
    private var queuePickerIndex = AtomicInteger()

    override fun onApplicationEvent(p0: ContextRefreshedEvent) {
        log.info { "Initializing ${javaClass.simpleName} by syncing executors." }
        // Synchronization might not be needed here, but for code consistency and for avoidance of thread issues is added.
        synchronized(executorLock) {
            addExecutorsFromDB()
        }
    }

    /**
     * Autograde maximum number of possible submissions assigned to this executor while considering current load.
     *
     * No guarantee is made how many submissions can be graded.
     *
     * @param executorId Which executor to use for grading?
     */
    private fun autograde(executorId: Long) {
        // Pointer, jobs may be added to it later
        var executorPriorityQueues = executors[executorId]?.values?.toList() ?: listOf()

        // Number of jobs planned to be executed, ensures that loop finishes
        val executableCount = min(
            executorPriorityQueues.sumOf { it.countWaiting() },
            getExecutorMaxLoad(executorId) - executorPriorityQueues.sumOf { it.countActive() }
        )

        repeat(executableCount) {
            executorPriorityQueues = executorPriorityQueues.filter { it.hasWaiting() }
            if (executorPriorityQueues.isEmpty()) return

            val queuePicker = queuePickerIndex.incrementAndGet().absoluteValue
            val item = executorPriorityQueues[queuePicker % executorPriorityQueues.size]
            item.start(1)
        }
    }

    //  fixedDelay doesn't start a next call before the last one has finished
    @Scheduled(fixedDelayString = "\${easy.core.auto-assess.fixed-delay.ms}")
    private fun grade() {
        log.debug { "Grading executors $executors" }
        // Synchronized as executors can be removed or added at any time.
        synchronized(executorLock) {
            executors.keys.forEach { executorId -> autograde(executorId) }
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
        val executor = synchronized(executorLock) {
            executors
                .getOrElse(targetExecutor.id) {
                    throw ExecutorException("Out of sync. Did you use API to add/remove executor '${targetExecutor.id}'?")
                }
                .getOrElse(priority) {
                    throw ExecutorException("Executor (${targetExecutor.id}) does not have queue with '$priority'.")
                }
        }
        return executor.scheduleAndAwait(targetExecutor, request)
    }

    /**
     *  Remove executor.
     */
    fun deleteExecutor(executorId: Long, force: Boolean) {
        // Synchronized as executors can be read or added at any time.
        synchronized(executorLock) {

            return transaction {
                val executorQuery = Executor.select { Executor.id eq executorId }
                val executorExists = executorQuery.count() == 1L
                // The load of the all the (priority) queues in the executor map of executor x.
                val currentLoad = executors[executorId]?.values?.sumOf { it.size() }

                if (!executorExists) {
                    throw InvalidRequestException("Executor with id $executorId not found")

                } else if (!force && (currentLoad ?: 0) > 0) {
                    throw InvalidRequestException("Executor load != 0 (is $currentLoad). Set 'force'=true for forced removal.")

                } else {
                    ExecutorContainerImage.deleteWhere { ExecutorContainerImage.executor eq executorId }
                    Executor.deleteWhere { Executor.id eq executorId }
                    executors.remove(executorId)
                    log.info { "Executor '$executorId' deleted" }
                }
            }
        }
    }


    fun addExecutorsFromDB() {
        // Synchronized as executors can be removed or read at any time.
        synchronized(executorLock) {
            val countBefore = executors.size

            getAvailableExecutorIds().forEach {
                executors.putIfAbsent(
                    // sortedMap does not need to be concurrent as all usages of this map are synchronized
                    it, sortedMapOf(
                        PriorityLevel.AUTHENTICATED to FunctionScheduler(::callExecutor),
                        PriorityLevel.ANONYMOUS to FunctionScheduler(::callExecutor)
                    )
                )
            }

            log.debug { "Checked for new executors. Executor count is now: $countBefore -> ${executors.size}." }
        }
    }
}
