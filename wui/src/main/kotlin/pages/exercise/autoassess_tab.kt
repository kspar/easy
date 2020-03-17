package pages.exercise

import spa.Component


class AutoAssessmentTabComp(
        private val scripts: List<AutoAssessScript>,
        private val onSaveUpdatedExercise: suspend (exercise: ExerciseDTO) -> Unit,
        parent: Component?
) : Component(parent) {

    data class AutoAssessScript(val filename: String, val content: String)

    override fun render(): String = "autoassessment tab"
}
