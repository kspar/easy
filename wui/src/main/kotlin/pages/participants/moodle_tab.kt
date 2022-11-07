package pages.participants

import Str
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

@ExperimentalStdlibApi
class ParticipantsMoodleTabComp(
    private val courseId: String,
    private val moodleStatus: ParticipantsRootComp.MoodleProps,
    private val onStudentsSynced: suspend () -> Unit,
//    private val groups: List<ParticipantsRootComp.Group>,
//    private val students: List<ParticipantsRootComp.Student>,
//    private val studentsPending: List<ParticipantsRootComp.PendingStudent>,
//    private val studentsMoodlePending: List<ParticipantsRootComp.PendingMoodleStudent>,
//    private val teachers: List<ParticipantsRootComp.Teacher>,
//    private val isEditable: Boolean,
//    private val onGroupDeleted: suspend () -> Unit,
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
            ButtonComp.Type.PRIMARY, "Sünkroniseeri õpilased", null, ::syncStudents,
            true, "Sünkroniseerin...", parent = this
        )
        syncGradesBtn = ButtonComp(
            ButtonComp.Type.PRIMARY, "Sünkroniseeri hinded", null, ::syncGrades,
            true, "Sünkroniseerin...", parent = this
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

    private suspend fun syncStudents() {
        debug { "Moodle syncing all students" }
        val moodleStudentsSyncStatus = fetchEms("/courses/$courseId/moodle/students", ReqMethod.POST,
            successChecker = { http200 }).await()
            .parseTo(MoodleSyncedStatus.serializer()).await().status

        awaitSyncEnd(moodleStudentsSyncStatus)

        debug { "Sync completed" }
        successMessage { "Õpilased edukalt sünkroniseeritud" }
    }

    private suspend fun syncGrades() {
        debug { "Moodle syncing all grades" }
        val moodleGradesSyncStatus = fetchEms("/courses/$courseId/moodle/grades", ReqMethod.POST,
            successChecker = { http200 }).await()
            .parseTo(MoodleSyncedStatus.serializer()).await().status

        awaitSyncEnd(moodleGradesSyncStatus)

        debug { "Sync completed" }
        successMessage { "Hinded edukalt sünkroniseeritud" }
    }

    private suspend fun awaitSyncEnd(status: MoodleSyncStatus) {
        if (status == MoodleSyncStatus.IN_PROGRESS) {
            debug { "Sync already in progress" }
            successMessage { "Sünkroniseerimine juba käib" }

            while (true) {
                sleep(3000).await()
                debug { "Polling moodle sync status" }

                val moodleProps = fetchEms(
                    "/courses/$courseId/moodle", ReqMethod.GET,
                    successChecker = { http200 }).await()
                    .parseTo(ParticipantsRootComp.MoodleStatus.serializer()).await().moodle_props

                if (moodleProps?.sync_students_in_progress != true) {
                    break
                }
            }
        }
    }

}