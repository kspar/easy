package pages.course_exercise

import kotlinx.coroutines.await
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise

class ExerciseAutoFeedbackHolderComp(
    private var autoFeedback: String?,
    private var failed: Boolean,
    private val canRetry: Boolean,
    parent: Component,
) : Component(parent) {

    private var feedbackComp: ExerciseAutoFeedbackComp? = null

    override val children
        get() = listOfNotNull(feedbackComp)

    override fun create() = doInPromise {
        feedbackComp = autoFeedback?.let {
            ExerciseAutoFeedbackComp(
                it, failed, canRetry,
                parent = this
            )
        }
    }

    fun clear() {
        feedbackComp = null
        rebuild()
    }

    suspend fun setFeedback(autoFeedback: String?, failed: Boolean) {
        this.autoFeedback = autoFeedback
        this.failed = failed
        createAndBuild().await()
    }
}