package core.ems.service.moodle

import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Service

@Service
class OnStartupMoodleActions(
    val moodleStudentsSyncService: MoodleStudentsSyncService,
    val moodleGradesSyncService: MoodleGradesSyncService
) : ApplicationListener<ContextRefreshedEvent> {

    // Release all locks on startup in case they were taken when the server was shut down
    override fun onApplicationEvent(p0: ContextRefreshedEvent) {
        moodleStudentsSyncService.syncStudentsLock.releaseAll()
        moodleGradesSyncService.syncGradesLock.releaseAll()
    }
}