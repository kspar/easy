package core.ems.cron


import core.ems.service.normaliseAllCourseExIndices
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component


@Component
class ExerciseIndexNormalisationCron {
    @Scheduled(cron = "\${easy.core.exercise-index-normalisation.cron}")
    fun cron() {
        normaliseAllCourseExIndices()
    }
}
