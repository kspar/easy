package core.aas

import com.fasterxml.jackson.annotation.JsonProperty
import core.db.*
import core.util.FunctionQueue
import kotlinx.coroutines.Dispatchers
import mu.KotlinLogging
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.*


private const val EXECUTOR_GRADE_URL = "/v1/grade"

private val log = KotlinLogging.logger {}


/**
 * Autoassess a solution to an automatic exercise.
 * The assessment is performed synchronously and may take a long time.
 *
 * @throws NoExecutorsException if there are no executors capable of assessing this exercise
 * @throws ExecutorOverloadException if all executors capable of assessing this exercise are already overloaded
 * @throws ExecutorException if an executor fails
 */
fun autoAssess(autoExerciseId: EntityID<Long>, submission: String): AutoAssessment {
    val autoExercise = getAutoExerciseDetails(autoExerciseId)
    val request = mapToExecutorRequest(autoExercise, submission)
    val executors = getCapableExecutors(autoExerciseId)
    val selectedExecutor = selectExecutor(executors)
    return callExecutor(selectedExecutor, request)
}


@Service
class FutureAutoGradeService {
    @Value("\${easy.core.auto-assess.timeout-check.clear-older-than.ms}")
    private lateinit var allowedRunningTimeMs: String

    /**
     * [submitAndAwait] expects that map of [executors] is synced with database. However [addExecutorsFromDB] can be called
     * anytime, therefore [executorLock] is used to synchronize [submitAndAwait] and [addExecutorsFromDB] do avoid
     * cases where [submitAndAwait] expects to see a executor in [executors] that otherwise might be already in db, but
     * not in map.
     */
    private val executorLock = "LOCK"

    val executors: MutableMap<Long, SortedMap<PriorityLevel, FunctionQueue<AutoAssessment>>> = mutableMapOf()

    //TODO: drain mode
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
        val queuesByPriority: SortedMap<PriorityLevel, FunctionQueue<AutoAssessment>> = executors[executorId]!!
        val jobsWaitingInEachQueue = queuesByPriority.values.map { it.inPending() }
        val totalJobsWaiting = jobsWaitingInEachQueue.sum()

        // Loads:
        val maxLoad = getExecutorMaxLoad(executorId)
        val currentLoad = queuesByPriority.values.sumOf { it.countActive() }
        val executorLoadAvail = maxLoad - currentLoad

        // Balance jobs so that every queue gets a chance to run.
        when {
            totalJobsWaiting < executorLoadAvail -> {
                // Case 1: executor has enough load to run all pending jobs (jobs pending at the time load was queried)
                queuesByPriority.values.zip(jobsWaitingInEachQueue) { queue, waitingJobs ->
                    queue.executeN(waitingJobs)
                }
            }
            // Case 2: more jobs are pending than there is available load.
            else -> {
                var i = 0 // Jobs currently executed
                var j = 0 // Current queue observed

                while (i < totalJobsWaiting) {

                    // Keep only queues that have pending jobs
                    val queues = queuesByPriority.values.filter { it.isPending() }

                    // Is there anything to execute?
                    if (queues.isEmpty()) {
                        break
                    }

                    // Only one queue remaining? No need to continue with while loop.
                    if (queues.size == 1) {
                        queues[0].executeN(totalJobsWaiting - i)
                        break
                    }

                    // Is still the index in bounds? If not, start from first queue.
                    if (j > queues.size - 1) {
                        j = 0
                    }

                    queues[j].executeN(1)
                    j++

                    i++
                }
            }
        }
    }

    //  fixedDelay doesn't start a next call before the last one has finished
    @Scheduled(fixedDelayString = "\${easy.core.auto-assess.fixed-delay.ms}")
    private fun grade() {
        executors.keys.forEach { executorId -> autograde(executorId) }
    }


    fun submitAndAwait(
        autoExerciseId: EntityID<Long>,
        submission: String,
        timeout: Long,
        priority: PriorityLevel
    ): AutoAssessment {
        val autoExercise = getAutoExerciseDetails(autoExerciseId)
        val request = mapToExecutorRequest(autoExercise, submission)

        // If here and the map is empty, then probably there has been a server restart, force sync.
        if (executors.isEmpty()) {
            addExecutorsFromDB()
        }

        synchronized(executorLock) {
            val executors = getCapableExecutors(autoExerciseId)
            val selected = selectExecutor(executors)

            log.debug { "Scheduling and waiting for priority '$priority' autoExerciseId '$autoExerciseId'." }

            return this.executors
                .getOrElse(selected.id) {
                    throw ExecutorException("Out of sync. Did you use API to add/remove executor '${selected.id}'?")
                }
                .getOrElse(priority) {
                    throw ExecutorException("Executor (${selected.id}) does not have queue with '$priority'.")
                }
                .submitAndAwait(
                    arrayOf(selected, request), timeout = timeout
                )
        }
    }


    @Scheduled(cron = "\${easy.core.auto-assess.timeout-check.cron}")
    @Synchronized
    private fun timeout() {
        val timeout = allowedRunningTimeMs.toLong()
        val removed = executors.values.flatMap { it.values }.sumOf { it.clearOlder(timeout) }
        log.debug { "Checked for timeout in scheduled call results: Removed '$removed' older than '$timeout' ms." }
    }

    fun addExecutorsFromDB() {
        synchronized(executorLock) {

            val new = getAvailableExecutorIds().filter {
                executors.putIfAbsent(
                    it, sortedMapOf(
                        Pair(
                            PriorityLevel.AUTHENTICATED,
                            FunctionQueue(::callExecutorInFutureJobService, Dispatchers.Default)
                        ),
                        Pair(
                            PriorityLevel.ANONYMOUS,
                            FunctionQueue(::callExecutorInFutureJobService, Dispatchers.Default)
                        )
                    )
                ) == null
            }.size
            log.debug { "Checked for new executors. Added total of '$new' executors." }
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
    val id: Long, val name: String, val baseUrl: String, val load: Int, val maxLoad: Int
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
                    it[AutoExercise.containerImage],
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
        (Executor innerJoin AutoExerciseExecutor)
            .select { AutoExerciseExecutor.autoExercise eq autoExerciseId }
            .map {
                CapableExecutor(
                    it[Executor.id].value,
                    it[Executor.name],
                    it[Executor.baseUrl],
                    it[Executor.load],
                    it[Executor.maxLoad]
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
