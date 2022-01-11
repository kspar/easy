package pages.sidenav

import Auth
import Role
import Str
import debug
import kotlinx.coroutines.await
import libheaders.MSidenavInstance
import libheaders.Materialize
import objOf
import rip.kspar.ezspa.*
import tmRender

enum class ActivePage {
    MY_COURSES, COURSE_EXERCISES, COURSE_GRADES, COURSE_PARTICIPANTS,
    LIBRARY,
    ARTICLES,
}

object Sidenav {

    data class Spec(
        val courseId: String? = null,
        val activePage: ActivePage? = null,
        val pageSection: PageSection? = null,
    )

    data class PageSection(
        val title: String,
        val items: List<Item>,
    )

    sealed class Item(
        open val iconHtml: String,
        open val text: String,
        val id: String = IdGenerator.nextId(),
    )

    data class Link(
        override val iconHtml: String,
        override val text: String,
        val href: String,
    ) : Item(iconHtml, text) {
        override fun toString() = "Link(text=$text, href=$href)"
    }

    data class Action(
        override val iconHtml: String,
        override val text: String,
        val onActivate: (a: Action) -> Unit,
    ) : Item(iconHtml, text) {
        override fun toString() = "Action(text=$text)"
    }


    private const val DST_ID = "sidenav-wrap"

    private lateinit var sidenavComp: SidenavRootComp

    suspend fun build() {
        sidenavComp = SidenavRootComp(Auth.activeRole, Auth.getAvailableRoles(), DST_ID)
        sidenavComp.createAndBuild().await()
    }

    fun refresh(spec: Spec) {
        doInPromise {
            sidenavComp.updateRole(Auth.activeRole).await()  // TODO: test, is this necessary?
            sidenavComp.updateCourse(spec.courseId).await()
            sidenavComp.updateActivePage(spec.activePage).await()
            sidenavComp.updatePageItems(spec.pageSection).await()
        }
    }
}


class SidenavRootComp(
    private var activeRole: Role,
    private val availableRoles: List<Role>,
    dstId: String
) : Component(null, dstId) {

    private var headSectionComp = SidenavHeadAccountSection(
        "${Auth.firstName} ${Auth.lastName}", Auth.email,
        activeRole, availableRoles, ::triggerRoleChange, this
    )

    private var generalSectionComp = SidenavGeneralSectionComp(activeRole, this, IdGenerator.nextId())

    private var courseSectionComp: SidenavCourseSectionComp? = null
    private val courseSectionDstId = IdGenerator.nextId()

    private var pageSectionComp: SidenavPageSectionComp? = null
    private val pageSectionDstId = IdGenerator.nextId()

    private var trailerSectionComp = SidenavTrailerSectionComp(activeRole, this, IdGenerator.nextId())

    private lateinit var mSidenavInstance: MSidenavInstance

    private var courseId: String? = null
    private var activePage: ActivePage? = null
    private var pageSection: Sidenav.PageSection? = null

    override val children: List<Component>
        get() = listOfNotNull(
            headSectionComp,
            generalSectionComp,
            courseSectionComp,
            pageSectionComp,
            trailerSectionComp
        )

    override fun render(): String = tmRender(
        "t-c-sidenav",
        "headSectionId" to headSectionComp.dstId,
        "generalSectionId" to generalSectionComp.dstId,
        "courseSectionId" to courseSectionDstId,
        "pageSectionId" to pageSectionDstId,
        "trailerSectionId" to trailerSectionComp.dstId,

        // TODO: rm when replaced with modal comp
        "newExerciseLabel" to "Uus ülesanne",
        "newExerciseTitleLabel" to "Ülesande nimi",
        "doSaveLabel" to Str.doSave(),
    )

    override fun postRender() {
        mSidenavInstance = Materialize.Sidenav.init(
            getElemById("sidenav"), objOf()
        )
    }

    fun updateRole(newRole: Role) = doInPromise {
        if (activeRole != newRole) {
            debug { "Sidenav updating based on new role" }
            activeRole = newRole

            generalSectionComp = SidenavGeneralSectionComp(activeRole, this, generalSectionComp.dstId)
            courseId?.let { courseId ->
                courseSectionComp = SidenavCourseSectionComp(activeRole, courseId, this, courseSectionDstId)
            }
            pageSection?.let { pageSection ->
                pageSectionComp = SidenavPageSectionComp(pageSection, this, pageSectionDstId)
            }
            trailerSectionComp = SidenavTrailerSectionComp(activeRole, this, trailerSectionComp.dstId)

            listOfNotNull(
                generalSectionComp.createAndBuild(),
                courseSectionComp?.createAndBuild(),
                pageSectionComp?.createAndBuild(),
                trailerSectionComp.createAndBuild()
            ).unionPromise().await()
        }
    }

    fun updateCourse(newCourseId: String?) = doInPromise {
        if (courseId != newCourseId) {
            debug { "Sidenav updating course section" }
            courseId = newCourseId

            when {
                newCourseId != null -> {
                    val comp = SidenavCourseSectionComp(activeRole, newCourseId, this, courseSectionDstId)
                    courseSectionComp = comp
                    comp.createAndBuild().await()
                }
                else -> {
                    courseSectionComp?.clear()
                    courseSectionComp = null
                }
            }
        }
    }

    fun updateActivePage(newActivePage: ActivePage?) = doInPromise {
        if (activePage != newActivePage) {
            debug { "Sidenav updating active page" }
            activePage = newActivePage

            generalSectionComp.clearAndSetActivePage(newActivePage)
            courseSectionComp?.clearAndSetActivePage(newActivePage)
        }
    }

    fun updatePageItems(newPageSection: Sidenav.PageSection?) = doInPromise {
        if (pageSection != newPageSection) {
            debug { "Sidenav updating page section" }
            pageSection = newPageSection

            when {
                newPageSection != null -> {
                    val comp = SidenavPageSectionComp(newPageSection, this, pageSectionDstId)
                    pageSectionComp = comp
                    comp.createAndBuild().await()
                }
                else -> {
                    pageSectionComp?.clear()
                    pageSectionComp = null
                }
            }
        }
    }

    private fun triggerRoleChange(newRole: Role) {
        Auth.switchToRole(newRole)
        EzSpa.PageManager.updatePage()
    }
}
