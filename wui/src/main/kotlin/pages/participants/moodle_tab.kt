package pages.participants

import Icons
import components.form.ButtonComp
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.sleep
import successMessage
import tmRender
import translation.Str

class ParticipantsMoodleTabComp(
    private val courseId: String,
    private val moodleStatus: ParticipantsRootComp.MoodleProps,
    private val onStudentsSynced: suspend () -> Unit,
    parent: Component?
) : Component(parent) {

    @Serializable
    data class MoodleSyncedStatus(
        val status: MoodleSyncStatus,
    )

    enum class MoodleSyncStatus { FINISHED, IN_PROGRESS }


    private lateinit var syncStudentsBtn: ButtonComp
    private lateinit var syncGradesBtn: ButtonComp

    override val children: List<Component>
        get() = listOf(syncStudentsBtn, syncGradesBtn)

    override fun create() = doInPromise {
        syncStudentsBtn = ButtonComp(
            ButtonComp.Type.PRIMARY, "Sünkroniseeri õpilased", Icons.doSync, ::syncStudents,
            moodleStatus.students_synced, "Sünkroniseerin...", parent = this
        )
        syncGradesBtn = ButtonComp(
            ButtonComp.Type.PRIMARY, "Sünkroniseeri hinded", Icons.doSync, ::syncGrades,
            moodleStatus.grades_synced, "Sünkroniseerin...", parent = this
        )
    }

    override fun render() = tmRender(
        "t-c-participants-moodle",
        "moodleShortnameLabel" to "Moodle'i kursuse lühinimi",
        "studentsSyncedLabel" to "Õpilased sünkroniseeritud",
        "gradesSyncedLabel" to "Hinded sünkroniseeritud",
        "studentsExplanation" to "Õpilasi sünkroniseeritakse automaatselt igal öösel. Soovi korral saad siin ka kohe " +
                "kõik õpilased Moodle'ist uuesti laadida, näiteks kui oled lisanud õpilasi Moodle'i kursusele juurde.",
        "gradesExplanation" to "Pärast igat õpilase esitust või ümberhindamist salvestatakse hinne automaatselt " +
                "Moodle'isse. Vajadusel saad siin ka kõik hinded uuesti sünkroniseerida, kuid üldiselt pole see vajalik.",
        "moodleShortname" to moodleStatus.moodle_short_name,
        "studentsSynced" to Str.translateBoolean(moodleStatus.students_synced),
        "gradesSynced" to Str.translateBoolean(moodleStatus.grades_synced),
        "studentsBtnId" to syncStudentsBtn.dstId,
        "gradesBtnId" to syncGradesBtn.dstId,
    )

    override fun postChildrenBuilt() {
        doInPromise {
            checkSyncStatuses()
        }
    }

    private suspend fun checkSyncStatuses() {
        val moodleProps = fetchEms(
            "/courses/$courseId/moodle", ReqMethod.GET,
            successChecker = { http200 }).await()
            .parseTo(ParticipantsRootComp.MoodleStatus.serializer()).await().moodle_props

        // Hack to show button clicked effects, start polling and restore active button after poll
        if (moodleProps?.sync_students_in_progress == true) {
            syncStudentsBtn.click()
        }
        if (moodleProps?.sync_grades_in_progress == true) {
            syncGradesBtn.click()
        }
    }

    private suspend fun syncStudents() {
        debug { "Moodle syncing all students" }
        val moodleStudentsSyncStatus = fetchEms("/courses/$courseId/moodle/students", ReqMethod.POST,
            successChecker = { http200 }).await()
            .parseTo(MoodleSyncedStatus.serializer()).await().status

        awaitSyncEnd(moodleStudentsSyncStatus, SyncType.STUDENTS)

        debug { "Sync completed" }
        successMessage { "Õpilased edukalt sünkroniseeritud" }

        onStudentsSynced()
    }

    private suspend fun syncGrades() {
        debug { "Moodle syncing all grades" }
        val moodleGradesSyncStatus = fetchEms("/courses/$courseId/moodle/grades", ReqMethod.POST,
            successChecker = { http200 }).await()
            .parseTo(MoodleSyncedStatus.serializer()).await().status

        awaitSyncEnd(moodleGradesSyncStatus, SyncType.GRADES)

        debug { "Sync completed" }
        successMessage { "Hinded edukalt sünkroniseeritud" }
    }


    private enum class SyncType { STUDENTS, GRADES }

    private suspend fun awaitSyncEnd(initialStatus: MoodleSyncStatus, type: SyncType) {
        if (initialStatus == MoodleSyncStatus.IN_PROGRESS) {
            debug { "Sync already in progress" }

            while (true) {
                sleep(3000).await()
                debug { "Polling moodle sync status" }

                val moodleProps = fetchEms(
                    "/courses/$courseId/moodle", ReqMethod.GET,
                    successChecker = { http200 }).await()
                    .parseTo(ParticipantsRootComp.MoodleStatus.serializer()).await().moodle_props

                val status = when (type) {
                    SyncType.STUDENTS -> moodleProps?.sync_students_in_progress
                    SyncType.GRADES -> moodleProps?.sync_grades_in_progress
                }

                // false (not in progress) or null (N/A, no moodle props)
                if (status != true)
                    break
            }
        }
    }

}