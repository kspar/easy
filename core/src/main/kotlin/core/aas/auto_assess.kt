package core.aas

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.exception.InvalidRequestException
import core.util.FunctionQueue
import kotlinx.coroutines.Job
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue
import kotlin.math.min


private const val EXECUTOR_GRADE_URL = "/v1/grade"

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

        log.debug { "Cleared $removed/$totalJobs auto-assess observer jobs that are NOT IN_PROGRESS." }
    }
}

@Service
class FutureAutoGradeService : ApplicationListener<ContextRefreshedEvent> {
    @Value("\${easy.core.auto-assess.timeout-check.clear-older-than.ms}")
    private lateinit var allowedRunningTimeMs: String

    @Value("\${easy.core.auto-assess.allowed-wait-for-user.ms}")
    private lateinit var allowedWaitingTimeUserMs: String

    /**
     * [submitAndAwait] expects that map of [executors] is synced with database. However [addExecutorsFromDB] can be called
     * anytime, therefore [executorLock] is used to synchronize [submitAndAwait] and [addExecutorsFromDB] to avoid
     * cases where [submitAndAwait] expects to see an executor in [executors] that otherwise might be already in db, but
     * not in map.
     */
    private val executorLock = UUID.randomUUID().toString()

    private val executors: MutableMap<Long, SortedMap<PriorityLevel, FunctionQueue<AutoAssessment>>> =
        ConcurrentHashMap()

    // Global index for picking the next queue
    private var queuePickerIndex = AtomicInteger()

    override fun onApplicationEvent(p0: ContextRefreshedEvent) {
        log.info { "Initializing FutureAutoGradeService by syncing executors." }
        // Synchronization might not be needed here, but for code consistency and for avoidance of thread issues is added.
        synchronized(executorLock) {
            addExecutorsFromDB()
        }
    }

    /**
     * Delegator for [callExecutor] as [callExecutor] is private, but reflective access is needed by [FunctionQueue].
     */
    fun callExecutorInFutureJobService(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
        return callExecutor(executor, request)
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
            getExecutorMaxLoad(executorId) - executorPriorityQueues.sumOf { it.countRunning().toInt() }
        )

        if (executableCount != 0) log.debug { "Executor '$executorId' is executing $executableCount jobs" }

        repeat(executableCount) {
            executorPriorityQueues = executorPriorityQueues.filter { it.hasWaiting() }
            if (executorPriorityQueues.isEmpty()) return

            val queuePicker = queuePickerIndex.incrementAndGet().absoluteValue
            val item = executorPriorityQueues[queuePicker % executorPriorityQueues.size]
            item.executeN(1)
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

        val executors = getCapableExecutors(autoExerciseId).filter { !it.drain }.toSet()
        val selected = selectExecutor(executors)

        log.debug { "Scheduling and waiting for priority '$priority' autoExerciseId '$autoExerciseId'." }

        // Synchronized as executors can be removed or added at any time.
        val executor = synchronized(executorLock) {
            this.executors
                .getOrElse(selected.id) {
                    throw ExecutorException("Out of sync. Did you use API to add/remove executor '${selected.id}'?")
                }
                .getOrElse(priority) {
                    throw ExecutorException("Executor (${selected.id}) does not have queue with '$priority'.")
                }
        }

        return executor.submitAndAwait(arrayOf(selected, request), timeout = allowedWaitingTimeUserMs.toLong())
    }


    @Scheduled(cron = "\${easy.core.auto-assess.timeout-check.cron}")
    @Synchronized
    private fun timeout() {
        val timeout = allowedRunningTimeMs.toLong()
        val removed = executors.values.flatMap { it.values }.sumOf { it.clearOlder(timeout) }
        log.trace { "Checked for timeout in scheduled call results: Removed '$removed' older than '$timeout' ms." }
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
            val executorIdsFromDB = getAvailableExecutorIds()

            val new = executorIdsFromDB.filter {
                executors.putIfAbsent(
                    // sortedMap does not need to be concurrent as all usages of this map are synchronized
                    it, sortedMapOf(
                        PriorityLevel.AUTHENTICATED to FunctionQueue(::callExecutorInFutureJobService),
                        PriorityLevel.ANONYMOUS to FunctionQueue(::callExecutorInFutureJobService)
                    )
                ) == null
            }.size
            log.debug { "Checked for new executors. Added total '$new' new executors." }
        }
    }
}


private data class AutoAssessExerciseDetails(
    val gradingScript: String, val containerImage: String, val maxTime: Int, val maxMem: Int,
    val assets: List<AutoAssessExerciseAsset>
)

private data class AutoAssessExerciseAsset(
    val fileName: String, val fileContent: String
)

data class CapableExecutor(
    val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int, val drain: Boolean
)

data class AutoAssessment(
    val grade: Int, val feedback: String
)


private fun getAutoExerciseDetails(autoExerciseId: EntityID<Long>): AutoAssessExerciseDetails {
    return transaction {
        val assets = Asset
            .select { Asset.autoExercise eq autoExerciseId }
            .map {
                AutoAssessExerciseAsset(
                    it[Asset.fileName],
                    it[Asset.fileContent]
                )
            }

        AutoExercise.select { AutoExercise.id eq autoExerciseId }
            .map {
                AutoAssessExerciseDetails(
                    it[AutoExercise.gradingScript],
                    it[AutoExercise.containerImage].value,
                    it[AutoExercise.maxTime],
                    it[AutoExercise.maxMem],
                    assets
                )
            }
            .first()
    }
}


data class ExecutorRequest(
    @JsonProperty("submission") val submission: String,
    @JsonProperty("grading_script") val gradingScript: String,
    @JsonProperty("assets") val assets: List<ExecutorRequestAsset>,
    @JsonProperty("image_name") val imageName: String,
    @JsonProperty("max_time_sec") val maxTime: Int,
    @JsonProperty("max_mem_mb") val maxMem: Int
)

data class ExecutorRequestAsset(
    @JsonProperty("file_name") val fileName: String,
    @JsonProperty("file_content") val fileContent: String
)


private fun mapToExecutorRequest(exercise: AutoAssessExerciseDetails, submission: String): ExecutorRequest =
    ExecutorRequest(
        submission,
        exercise.gradingScript,
        exercise.assets.map { ExecutorRequestAsset(it.fileName, it.fileContent) },
        exercise.containerImage,
        exercise.maxTime,
        exercise.maxMem
    )

private fun getCapableExecutors(autoExerciseId: EntityID<Long>): Set<CapableExecutor> {
    return transaction {
        (AutoExercise innerJoin ContainerImage innerJoin ExecutorContainerImage innerJoin Executor)
            .select { AutoExercise.id eq autoExerciseId }
            .map {
                CapableExecutor(
                    it[Executor.id].value,
                    it[Executor.name],
                    it[Executor.baseUrl],
                    it[Executor.load],
                    it[Executor.maxLoad],
                    it[Executor.drain]
                )
            }
            .toSet()
    }
}

private fun selectExecutor(executors: Set<CapableExecutor>): CapableExecutor {
    if (executors.isEmpty()) {
        throw NoExecutorsException("No capable executors found for this auto exercise")
    }

    val executor = executors.reduce { bestExec, currentExec ->
        if (currentExec.load / currentExec.maxLoad < bestExec.load / bestExec.maxLoad) currentExec else bestExec
    }

    if (executor.load >= executor.maxLoad) {
        throw ExecutorOverloadException("All capable executors at max load")
    }
    return executor
}


data class ExecutorResponse(
    @JsonProperty("grade") val grade: Int,
    @JsonProperty("feedback") val feedback: String
)

private fun callExecutor(executor: CapableExecutor, request: ExecutorRequest): AutoAssessment {
    incExecutorLoad(executor.id)
    try {
        log.info { "Calling executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }

        val template = RestTemplate()
        val responseEntity = template.postForEntity(
            executor.baseUrl + EXECUTOR_GRADE_URL, request, ExecutorResponse::class.java
        )

        if (responseEntity.statusCode.isError) {
            log.error { "Executor error ${responseEntity.statusCodeValue} with request $request" }
            throw ExecutorException("Executor error (${responseEntity.statusCodeValue})")
        }

        val response = responseEntity.body
        if (response == null) {
            log.error { "Executor response is empty with request $request" }
            throw ExecutorException("Executor error (empty body)")
        }

        return AutoAssessment(response.grade, response.feedback)

    } finally {
        decExecutorLoad(executor.id)
        log.info { "Call finished to executor ${executor.name}, load is now ${getExecutorLoad(executor.id)}" }
    }
}

private fun incExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(load, load + 1)
            }
        }
    }
}

private fun decExecutorLoad(executorId: Long) {
    transaction {
        Executor.update({ Executor.id eq executorId }) {
            with(SqlExpressionBuilder) {
                it.update(load, load - 1)
            }
        }
    }
}

private fun getExecutorLoad(executorId: Long): Int {
    return transaction {
        Executor.slice(Executor.load)
            .select { Executor.id eq executorId }
            .map { it[Executor.load] }[0]
    }
}

private fun getExecutorMaxLoad(executorId: Long): Int {
    return transaction {
        Executor.slice(Executor.maxLoad)
            .select { Executor.id eq executorId }
            .map { it[Executor.maxLoad] }[0]
    }
}


private fun getAvailableExecutorIds(): List<Long> {
    return transaction {
        Executor.slice(Executor.id).selectAll().map { it[Executor.id].value }
    }
}
