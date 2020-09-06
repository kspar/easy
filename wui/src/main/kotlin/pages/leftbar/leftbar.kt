package pages.leftbar

import Auth
import Role
import Str
import buildStatics
import debug
import kotlinx.coroutines.await
import kotlinx.dom.addClass
import kotlinx.dom.removeClass
import kotlinx.serialization.Serializable
import libheaders.MSidenavInstance
import libheaders.Materialize
import objOf
import onSingleClickWithDisabled
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import queries.*
import rip.kspar.ezspa.*
import tmRender
import kotlin.js.Promise

object Leftbar {

    data class Conf(val courseId: String? = null)

    private const val DST_ID = "leftbar-wrap"

    private var leftbarComp: LeftbarRootComp? = null

    fun refresh(conf: Conf) {
        val activeRole = Auth.activeRole
        if (leftbarComp?.activeRole != activeRole || leftbarComp?.courseId != conf.courseId) {
            debug { "Rebuilding leftbar" }
            doInPromise {
                leftbarComp = LeftbarRootComp(activeRole, conf.courseId, Auth.hasRole(Role.STUDENT),
                        Auth.hasRole(Role.TEACHER), Auth.hasRole(Role.ADMIN), DST_ID).also {
                    it.createAndBuild().await()
                }
                getBody().addClass("leftbar-active")
            }
        }
    }

    fun handleLeftbarToggleClick() {
        leftbarComp?.handleToggleClick()
    }
}


class LeftbarRootComp(
        val activeRole: Role,
        val courseId: String?,
        private val isStudent: Boolean,
        private val isTeacher: Boolean,
        private val isAdmin: Boolean,
        dstId: String
) : Component(null, dstId) {

    @Serializable
    private data class NewCourseDTO(val id: String)

    @Serializable
    private data class NewExerciseDTO(val id: String)

    private data class AvailableRole(val id: String, val name: String, val authRole: Role, val isSelected: Boolean)


    private val rolesList = mapOf(
            AvailableRole("admin", "Admin", Role.ADMIN, activeRole == Role.ADMIN) to isAdmin,
            AvailableRole("teacher", "Õpetaja", Role.TEACHER, activeRole == Role.TEACHER) to isTeacher,
            AvailableRole("student", "Õpilane", Role.STUDENT, activeRole == Role.STUDENT) to isStudent
    ).filter { it.value }.map { it.key }

    private var courseTitle: String? = null
    private lateinit var mLeftbarInstance: MSidenavInstance

    override fun create(): Promise<*> = doInPromise {
        if (courseId != null) {
            courseTitle = BasicCourseInfo.get(courseId).await().title
        }
    }

    override fun render(): String = tmRender("t-c-leftbar",
            "userName" to "${Auth.firstName} ${Auth.lastName}",
            "userEmail" to Auth.email,
            "canSwitchRole" to (rolesList.size > 1),
            "userRole" to Str.translateRole(activeRole),
            "rolesAvailable" to rolesList.map { mapOf("id" to it.id, "name" to it.name, "isSelected" to it.isSelected) },

            "isTeacherOrAdmin" to isTeacherOrAdmin(activeRole),
            "isAdmin" to (activeRole == Role.ADMIN),
            "isCourseActive" to (courseId != null),
            "courseId" to courseId,
            "courseTitle" to courseTitle,

            "myCoursesLabel" to "Minu kursused",
            "exerciseLibLabel" to "Ülesandekogu",
            "articlesLabel" to "Artiklid",
            "newCourseLabel" to "Uus kursus",
            "exercisesLabel" to "Ülesanded",
            "gradesLabel" to "Hinded",
            "participantsLabel" to "Osalejad",
            "newExerciseLabel" to "Uus ülesanne",
            "addExerciseLabel" to "Lisa olemasolev ülesanne",
            "accountSettingsLink" to Auth.createAccountUrl(),
            "accountSettingsLabel" to "Konto seaded",
            "logOutLink" to Auth.createLogoutUrl(),
            "logOutLabel" to "Logi välja",
            "newCourseTitleLabel" to "Kursuse nimi",
            "doSaveLabel" to Str.doSave(),
            "newExerciseTitleLabel" to "Ülesande nimi"
    )

    override fun postRender() {
        mLeftbarInstance = Materialize.Sidenav.init(getElemById("leftbar"), objOf(
                "onOpenStart" to ::onOpen,
                "onCloseStart" to ::onClose,
        ))
        initSelectRoleChange()
        initNewCourseModal()
        initNewExerciseModal()
    }

    fun handleToggleClick() {
        if (mLeftbarInstance.isOpen) {
            debug { "Closing sidenav" }
            mLeftbarInstance.close()

        } else {
            debug { "Opening sidenav" }
            mLeftbarInstance.open()
        }
    }

    private fun onOpen() {
        getBody().addClass("leftbar-active")
    }

    private fun onClose() {
        getBody().removeClass("leftbar-active")
    }

    private fun isTeacherOrAdmin(role: Role) = role == Role.TEACHER || role == Role.ADMIN

    private fun initSelectRoleChange() {
        val roleSelect = getElemByIdAsOrNull<HTMLSelectElement>("select-role")
        if (roleSelect != null) {
            Materialize.FormSelect.init(roleSelect, objOf(
                    "classes" to "role-select",
                    "dropdownOptions" to objOf(
                            "coverTrigger" to false,
                            "autoFocus" to false
                    )
            ))
            roleSelect.onChange {
                closeLeftbarOnSmall()
                val newRoleId = roleSelect.value
                debug { "Change role to $newRoleId" }
                val newRole = rolesList.first { it.id == newRoleId }.authRole
                Auth.switchToRole(newRole)
                EzSpa.PageManager.updatePage()
            }
        }
    }

    private fun initNewCourseModal() {
        val modal = Materialize.Modal.init(getElemById("new-course-modal"))
        getElemByIdAs<HTMLButtonElement>("new-course-btn").onSingleClickWithDisabled(Str.saving()) {
            val courseTitle = getElemByIdAs<HTMLInputElement>("new-course-name-input").value
            debug { "Saving new course with title $courseTitle" }
            val courseId = fetchEms("/admin/courses", ReqMethod.POST, mapOf("title" to courseTitle),
                    successChecker = { http200 }).await()
                    .parseTo(NewCourseDTO.serializer()).await().id
            debug { "Saved new course with id $courseId" }
            modal.close()
            EzSpa.PageManager.navigateTo("/courses/$courseId/exercises")
        }
    }

    private fun initNewExerciseModal() {
        val modal = Materialize.Modal.init(getElemById("new-exercise-modal"))
        getElemByIdAs<HTMLButtonElement>("new-exercise-btn").onSingleClickWithDisabled(Str.saving()) {
            val exerciseTitle = getElemByIdAs<HTMLInputElement>("new-exercise-name-input").value
            debug { "Saving new exercise with title $exerciseTitle" }
            val exerciseId = fetchEms("/exercises", ReqMethod.POST, mapOf("title" to exerciseTitle, "public" to false, "grader_type" to "TEACHER"),
                    successChecker = { http200 }).await().parseTo(NewExerciseDTO.serializer()).await().id
            debug { "Saved new exercise with id $exerciseId" }
            modal.close()
            EzSpa.PageManager.navigateTo("/exercises/$exerciseId/details")
        }
    }

    private fun closeLeftbarOnSmall() {
        mLeftbarInstance.close()
    }
}
